package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.Email.Flag
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Everything an importer needs about its account, read once while a transaction was open. The job
 * outlives that transaction by hours, which a DAO entity would not: it could no longer resolve
 * [userId] from its reference.
 */
data class ImapConnection(
    val id: Uuid,
    val userId: Uuid,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    /** Changes to any of these mean the connection has to be rebuilt, see `ImporterManager`. */
    val signature: String get() = "$host:$port:$username:$password"
}

class EmailImporter(
    private val database: OvermailDatabase,
    val account: ImapConnection,
    private val coroutineScope: CoroutineScope,
) {

    private var importerJob: Job? = null

    fun start() {
        importerJob = coroutineScope.launch {

            val client = ImapClient(
                host = account.host,
                port = account.port,
                username = account.username,
                password = account.password,
                debug = false,
            )

            client.testConnection()

            val folders = client.getFolders()
            val inbox = folders.firstOrNull { it.name == "INBOX" }

            if (inbox == null) {
                println("No INBOX folder found for account ${account.username}")
                return@launch
            }

            while (isActive) {
                val mails = inbox.getMails {
                    getAll()
                    envelope = true
                    flags = true
                    uid = true
                }
                mails.forEach { mail ->
                    // A missing subject stores as "", never null: the dedup below compares it with
                    // `=`, and NULL never equals NULL, so such mails would import over and over.
                    val subject = mail.subject.await().orEmpty()
                    val sentAt = mail.sentAt.await()

                    // Before the body, not after: downloading it pulls the attachments too.
                    if (database.query { isKnown(sentAt, subject) }) return@forEach

                    val from = mail.from.await()
                    val to = mail.to.await()
                    val cc = mail.cc.await()
                    val bcc = mail.bcc.await()

                    // Only the address identifies a stored email user. The display names stay on
                    // this mail: notifications@github.com carries the acting username as its name,
                    // so a name learned here says nothing about the next mail from that address.
                    val emailUsers = findOrCreateEmailUsers((from + to + cc + bcc).map { it.address }.distinct())

                    val fromHeader = from.firstOrNull()
                    if (fromHeader == null) {
                        println("Skipping mail without a From header: $subject")
                        return@forEach
                    }

                    val recipients = listOf(
                        to to EmailRecipientType.RECIPIENT,
                        cc to EmailRecipientType.CC,
                        bcc to EmailRecipientType.BCC,
                    ).flatMap { (users, type) ->
                        users.map { NewRecipient(emailUsers.getValue(it.address), it.name, type) }
                    }

                    val raw = ByteArrayOutputStream()
                    val text = ByteArrayOutputStream()
                    val html = ByteArrayOutputStream()
                    // getContent parses through a piped stream and blocks the calling thread.
                    withContext(Dispatchers.IO) { mail.content.getContent(raw, text, html) }

                    val stored = insert(
                        senderId = emailUsers.getValue(fromHeader.address),
                        senderName = fromHeader.name,
                        subject = subject,
                        sent = sentAt,
                        rawContent = raw.toByteArray(),
                        textContent = text.toByteArray().decodeToString().takeIf { it.isNotBlank() },
                        htmlContent = html.toByteArray().decodeToString().takeIf { it.isNotBlank() },
                        isRead = Flag.Seen in mail.flags.await(),
                        recipients = recipients,
                    )

                    if (stored != null) println("Imported: $subject")
                }
                delay(5.minutes)
            }
        }
    }

    fun stop() {
        importerJob?.cancel()
        importerJob = null
    }

    /**
     * Resolves the header addresses to [EmailUsers] ids, inserting the ones this user has not seen
     * before. No upsert: the row holds nothing but the key, so there would be nothing to update.
     * `insertIgnore` returns null once the address is known -- including the row an importer of
     * another account of the same user just committed -- and the lookup then finds it.
     */
    private suspend fun findOrCreateEmailUsers(addresses: List<String>): Map<String, Uuid> = database.query {
        addresses.associateWith { address ->
            EmailUsers.insertIgnoreAndGetId {
                it[user] = account.userId
                it[EmailUsers.address] = address
            }?.value
                ?: EmailUsers
                    .select(EmailUsers.id)
                    .where { (EmailUsers.user eq account.userId) and (EmailUsers.address eq address) }
                    .single()[EmailUsers.id].value
        }
    }

    /**
     * Stores the mail together with its recipient links, or returns null and writes nothing if it
     * is already there. Never updates an existing mail: the local state (`is_read`) is ours, the
     * server's copy must not overwrite it.
     */
    private suspend fun insert(
        senderId: Uuid,
        senderName: String?,
        subject: String,
        sent: Instant,
        rawContent: ByteArray,
        textContent: String?,
        htmlContent: String?,
        isRead: Boolean,
        recipients: List<NewRecipient>,
    ): Uuid? = database.query {
        // Check and insert share this transaction. The dedup key has no unique index (the subject
        // is `text` and can blow the btree key limit), so the constraint cannot do it for us --
        // but ImporterManager keeps one importer per account, so there is no second writer.
        if (isKnown(sent, subject)) return@query null

        val emailId = Emails.insertAndGetId {
            it[imapAccount] = account.id
            it[sender] = senderId
            it[Emails.senderName] = senderName
            it[Emails.subject] = subject
            it[Emails.sent] = sent.truncatedToSecond()
            it[Emails.rawContent] = rawContent
            it[Emails.textContent] = textContent
            it[Emails.htmlContent] = htmlContent
            it[Emails.isRead] = isRead
        }.value

        recipients
            // The unique index is (mail, address, field), so an address listed twice in the same
            // field has to collapse into one row. Sorting first lets the named entry win.
            .sortedBy { it.name == null }
            .distinctBy { it.emailUserId to it.type }
            .forEach { recipient ->
                EmailRecipients.insert {
                    it[email] = emailId
                    it[emailUser] = recipient.emailUserId
                    it[name] = recipient.name
                    it[type] = recipient.type
                }
            }

        emailId
    }

    /** Mails are recognised by account, send second and subject, see [Emails]. */
    private fun isKnown(sent: Instant, subject: String): Boolean =
        Emails
            .select(Emails.id)
            .where {
                (Emails.imapAccount eq account.id) and
                    (Emails.sent eq sent.truncatedToSecond()) and
                    (Emails.subject eq subject)
            }
            .empty()
            .not()

    private data class NewRecipient(
        val emailUserId: Uuid,
        val name: String?,
        val type: EmailRecipientType,
    )
}

package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.Email
import es.jvbabi.overmail.core.Email.Flag
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailPreviews
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import es.jvbabi.overmail.server.util.mailPreview
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val POLL_INTERVAL = 5.minutes

/**
 * How often a watched folder re-issues its `IDLE`.
 *
 * RFC 2177 tells clients to renew at least every 29 minutes, and middleboxes drop an idle socket
 * long before a server would -- a watch that is never renewed goes quiet without ever failing,
 * which is the one way of breaking that nothing here would notice.
 */
private val IDLE_RENEW_INTERVAL = 25.minutes

/** How long a watch waits before reconnecting. The poll keeps running meanwhile, so mail is not lost. */
private val IDLE_RETRY_INTERVAL = 30.seconds

/** What a decoder puts where a byte sequence made no sense. */
private const val REPLACEMENT_CHARACTER = '\uFFFD'

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
    /** The folders this account syncs, and how. Empty means nothing is imported for it. */
    val folders: List<FolderSync>,
    /** Whether the account is paused; a paused one has no importer at all. */
    val isPaused: Boolean = false,
) {
    /** Changes to any of these mean the connection has to be rebuilt, see `ImporterManager`. */
    val signature: String
        get() = "$host:$port:$username:$password:" +
            folders.sortedBy { it.folder }.joinToString(",") { "${it.folder}/${it.imapPush}/${it.aiImport}/${it.createdAt}" }

    /** One folder's settings, as `ImapAccountFolderSyncs` holds them. */
    data class FolderSync(
        val folder: String,
        /** Whether the folder is watched over an open connection rather than only polled. */
        val imapPush: Boolean,
        val aiImport: ImapAccountFolderSync.AiImportSettings,
        /** When the folder was added, which is what "only new messages" is measured against. */
        val createdAt: Instant,
    ) {
        /**
         * Whether a mail sent at [sentAt] is worth putting through the assistant.
         *
         * Every mail of a synced folder is imported either way -- this only decides what the
         * assistant is paid to read, which is what the user picked per folder.
         */
        fun wantsAssistant(sentAt: Instant): Boolean = when (val scope = aiImport) {
            ImapAccountFolderSync.AiImportSettings.AllMessages -> true
            // Everything already in the folder when it was added is history; "only new" means
            // what arrives from here on.
            ImapAccountFolderSync.AiImportSettings.OnlyNewMessages -> sentAt >= createdAt
            is ImapAccountFolderSync.AiImportSettings.AfterDate -> sentAt >= scope.date
        }
    }
}

class EmailImporter(
    private val database: OvermailDatabase,
    val account: ImapConnection,
    private val coroutineScope: CoroutineScope,
    private val emailClassificationQueue: EmailClassificationQueue,
    private val mailNotifier: MailNotifier,
) {

    private val logger = LoggerFactory.getLogger(EmailImporter::class.java)

    private var importerJob: Job? = null

    /**
     * Many events between two passes are one reason to look again, so the newest wins and the
     * older ones are dropped: what a watch reports is "something changed", never which mail.
     */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        importerJob = coroutineScope.launch {
            // Started together with the pass below, not after it: a mailbox with years of mail in
            // it takes a long time to walk, and a watch that waited for that would miss every mail
            // arriving meanwhile -- which is the mail the user is actually waiting for.
            val watches = account.folders
                .filter { it.imapPush }
                .map { folder -> launch { watch(folder) } }

            try {
                while (isActive) {
                    // One failed cycle must not end the job: nothing restarts it (ImporterManager
                    // only reacts to config changes), so an uncaught error would stop the import
                    // for good.
                    try {
                        importOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Import cycle failed for ${account.username}, retrying in $POLL_INTERVAL", e)
                    }
                    // Whichever comes first: the timer, or a watch saying a folder changed.
                    withTimeoutOrNull(POLL_INTERVAL) { wakeUps.receive() }
                }
            } finally {
                watches.forEach { it.cancel() }
            }
        }
    }

    /**
     * Holds an `IDLE` on [sync] and asks for a pass whenever the folder reports a change.
     *
     * A pass rather than a targeted fetch: `* n EXISTS` says how many mails the folder has now,
     * not which one is new, so there is nothing to fetch by. Looking again is cheap -- the pass
     * skips what it already has.
     *
     * Its own connection, because that is what `IDLE` is: a socket that says nothing until it has
     * something to say, and can therefore not be shared with the commands the pass runs.
     */
    private suspend fun watch(sync: ImapConnection.FolderSync) {
        while (coroutineContext.isActive) {
            try {
                ImapClient(
                    host = account.host,
                    port = account.port,
                    username = account.username,
                    password = account.password,
                    debug = false,
                ).use { client ->
                    val folder = client.getFolders().firstOrNull { it.fullName == sync.folder }
                    if (folder == null) {
                        logger.warn("Cannot watch ${sync.folder} for ${account.username}: no such folder")
                        return
                    }

                    folder.getIdleFolder().use { idleFolder ->
                        while (coroutineContext.isActive) {
                            // Re-issued on a timer: an IDLE nobody renews is dropped by the
                            // server or by whatever sits between, and it goes quiet rather than
                            // failing, so nothing here would ever notice.
                            withTimeoutOrNull(IDLE_RENEW_INTERVAL) {
                                idleFolder.idle {
                                    onNewMessage { wakeUps.trySend(Unit) }
                                    onRemovedMessage { wakeUps.trySend(Unit) }
                                    onFlagChanged { _, _ -> wakeUps.trySend(Unit) }
                                }
                            }
                            idleFolder.cancel()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The poll keeps running regardless, so a watch that cannot hold its connection
                // costs latency, not mail.
                logger.warn("Watch on ${sync.folder} for ${account.username} failed, retrying in $IDLE_RETRY_INTERVAL", e)
                delay(IDLE_RETRY_INTERVAL)
            }
        }
    }

    /**
     * One poll cycle over every synced folder, on a connection of its own. Not reused across
     * cycles: the pool inside [ImapClient] never evicts sockets the server has dropped in the
     * meantime and hands them out again -- a dead socket can even yield an empty mail list instead
     * of an error, which would look like an empty inbox forever.
     */
    private suspend fun importOnce() {
        if (account.folders.isEmpty()) return

        ImapClient(
            host = account.host,
            port = account.port,
            username = account.username,
            password = account.password,
            debug = false,
        ).use { client ->
            val byName = client.getFolders().associateBy { it.fullName }

            account.folders.forEach { sync ->
                val folder = byName[sync.folder]
                if (folder == null) {
                    logger.warn("No folder ${sync.folder} for ${account.username}; it may have been renamed")
                    return@forEach
                }

                folder.use { selected ->
                    val mails = selected.getMails {
                        getAll()
                        envelope = true
                        flags = true
                        uid = true
                    }
                    mails.forEach { mail ->
                        // The only place this cycle may be stopped: see the NonCancellable below.
                        coroutineContext.ensureActive()
                        try {
                            import(mail, sync)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Skip only this mail; unknown ones are retried next cycle anyway.
                            logger.error("Failed to import a mail for ${account.username}", e)
                        }
                    }
                }
            }
        }
    }

    /**
     * Imports one mail, all of it or none of it.
     *
     * `NonCancellable`, so stopping the importer never lands between the body being downloaded and
     * the row being written -- the mail is finished, and the cycle stops at the check before the
     * next one. It is the whole reason [stop] can promise a clean end.
     */
    private suspend fun import(mail: Email, sync: ImapConnection.FolderSync) = withContext(NonCancellable) {
        // A missing subject stores as "", never null: the dedup below compares it with
        // `=`, and NULL never equals NULL, so such mails would import over and over.
        val subject = mail.subject.await().orEmpty()
        val sentAt = mail.sentAt.await()

        // Before the body, not after: downloading it pulls the attachments too.
        if (database.query { isKnown(sentAt, subject) }) return@withContext

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
            logger.warn("Skipping mail without a From header: $subject")
            return@withContext
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

        val storedId = insert(
            senderId = emailUsers.getValue(fromHeader.address),
            senderName = fromHeader.name,
            subject = subject,
            sent = sentAt,
            rawContent = raw.toByteArray(),
            textContent = text.toByteArray().decodeMailPart("text", subject),
            htmlContent = html.toByteArray().decodeMailPart("html", subject),
            isRead = Flag.Seen in mail.flags.await(),
            recipients = recipients,
        )

        if (storedId != null) {
            // Imported either way; only what the assistant reads is the user's choice per folder.
            if (sync.wantsAssistant(sentAt)) emailClassificationQueue.enqueue(storedId)
            // The mail is in the mailbox now, so anything showing or counting it is stale.
            // A mail that was not there before: every listing is one longer and one row further down.
            mailNotifier.notifyMailChanged(account.userId, storedId, movedListings = true)
        }
    }

    /**
     * Stops the importer and waits for it to be done.
     *
     * Suspending on purpose: a mail that is halfway through being written finishes first (see
     * [import]), so nothing is left behind half-imported and the connections are closed by the
     * time this returns. The caller replacing this importer with a new one therefore never has
     * two of them on the same mailbox.
     */
    suspend fun stop() {
        importerJob?.cancelAndJoin()
        importerJob = null
    }

    /**
     * A body part as text. UTF-8, always: that is what the mail library hands over, whatever
     * charset the part declared -- and the columns behind this are UTF-8 as well, so the bytes
     * are decoded exactly once, here.
     *
     * A part that does not decode cleanly comes back with replacement characters rather than
     * throwing, because half a mail beats no mail -- but it is worth a line in the log, or a
     * charset the library cannot read would quietly turn into a mailbox full of question marks.
     */
    private fun ByteArray.decodeMailPart(part: String, subject: String): String? {
        val decoded = decodeToString()
        if (decoded.contains(REPLACEMENT_CHARACTER)) {
            logger.warn("The $part part of \"$subject\" did not decode cleanly; it is stored as it came out")
        }

        return decoded.takeIf { it.isNotBlank() }
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

        // Written here rather than left to the queue: the body is parsed anyway, so the preview
        // costs nothing at this point, and a mail is in a listing the moment it is imported.
        EmailPreviews.upsert {
            it[email] = emailId
            it[preview] = mailPreview(textContent, htmlContent)
        }

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

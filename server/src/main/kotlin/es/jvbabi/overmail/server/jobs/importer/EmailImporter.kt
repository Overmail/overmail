package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.Email.Flag
import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.domain.models.EmailRecipientType
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes

class EmailImporter(
    val imapAccount: ImapAccount,
    val emailUserRepository: EmailUserRepository,
    val emailRepository: EmailRepository,
    val coroutineScope: CoroutineScope
) {

    private var importerJob: Job? = null

    companion object {
        fun buildImapConnectionSignature(imapAccount: ImapAccount): String {
            return "${imapAccount.host}:${imapAccount.port}:${imapAccount.username}:${imapAccount.password}"
        }
    }

    fun start() {
        importerJob = coroutineScope.launch {

            val client = ImapClient(
                host = imapAccount.host,
                port = imapAccount.port,
                username = imapAccount.username,
                password = imapAccount.password,
                debug = false,
            )

            client.testConnection()

            val folders = client.getFolders()
            val inbox = folders.firstOrNull { it.name == "INBOX" }

            if (inbox == null) {
                println("No INBOX folder found for account ${imapAccount.username}")
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
                    if (emailRepository.findDuplicate(imapAccount, sentAt, subject).first() != null) {
                        return@forEach
                    }

                    val from = mail.from.await()
                    val to = mail.to.await()
                    val cc = mail.cc.await()
                    val bcc = mail.bcc.await()

                    // Kamel's EmailUser compares by address *and* name, so one address can appear
                    // twice with different names. Sorting first lets the named entry win.
                    val emailUsers = (from + to + cc + bcc)
                        .sortedBy { it.name == null }
                        .distinctBy { it.address }
                        .associate { it.address to emailUserRepository.upsert(imapAccount.user, it.name, it.address) }

                    val sender = from.firstOrNull()?.let { emailUsers.getValue(it.address) }
                    if (sender == null) {
                        println("Skipping mail without a From header: $subject")
                        return@forEach
                    }

                    val recipients = listOf(
                        to to EmailRecipientType.RECIPIENT,
                        cc to EmailRecipientType.CC,
                        bcc to EmailRecipientType.BCC,
                    ).flatMap { (users, type) -> users.map { emailUsers.getValue(it.address) to type } }

                    val raw = ByteArrayOutputStream()
                    val text = ByteArrayOutputStream()
                    val html = ByteArrayOutputStream()
                    // getContent parses through a piped stream and blocks the calling thread.
                    withContext(Dispatchers.IO) { mail.content.getContent(raw, text, html) }

                    emailRepository.insert(
                        imapAccount = imapAccount,
                        sender = sender,
                        subject = subject,
                        sent = sentAt,
                        rawContent = raw.toByteArray(),
                        textContent = text.toByteArray().decodeToString().takeIf { it.isNotBlank() },
                        htmlContent = html.toByteArray().decodeToString().takeIf { it.isNotBlank() },
                        isRead = Flag.Seen in mail.flags.await(),
                        recipients = recipients,
                    )

                    println("Imported: $subject")
                }
                delay(5.minutes)
            }
        }
    }

    fun stop() {
        importerJob?.cancel()
        importerJob = null
    }
}
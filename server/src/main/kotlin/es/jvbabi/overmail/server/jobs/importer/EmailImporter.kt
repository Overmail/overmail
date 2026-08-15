package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.core.ImapClient
import es.jvbabi.overmail.server.domain.models.ImapAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class EmailImporter(
    val imapAccount: ImapAccount,
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
                    println("Email: ${mail.subject.await()} - Flags: ${mail.flags.await()}")
                }
                delay(5.seconds)
            }
        }
    }

    fun stop() {
        importerJob?.cancel()
        importerJob = null
    }
}
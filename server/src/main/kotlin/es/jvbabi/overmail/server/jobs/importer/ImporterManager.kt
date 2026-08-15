package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepository
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlin.uuid.Uuid

class ImporterManager(
    private val database: OvermailDatabase,
    private val imapAccountRepository: ImapAccountRepository,
    private val emailUserRepository: EmailUserRepository,
    private val emailRepository: EmailRepository,
    private val coroutineScope: CoroutineScope,
) {

    private val importer = mutableMapOf<Uuid, EmailImporter>()

    suspend fun start() {
        imapAccountRepository
            .getAll()
            .collect { accounts ->
                val currentIds = accounts.map { it.id }.toSet()
                importer.keys.filterNot { it in currentIds }.forEach { removedId ->
                    importer.remove(removedId)?.stop()
                }

                accounts.forEach { account ->
                    val existingImporter = importer[account.id]
                    if (existingImporter != null) {
                        val existingSignature = EmailImporter.buildImapConnectionSignature(existingImporter.imapAccount)
                        val newSignature = EmailImporter.buildImapConnectionSignature(account)
                        if (existingSignature == newSignature) return@forEach
                        existingImporter.stop()
                    }

                    val newImporter = EmailImporter(
                        imapAccount = account,
                        emailUserRepository = emailUserRepository,
                        emailRepository = emailRepository,
                        coroutineScope = CoroutineScope(coroutineScope.coroutineContext) + CoroutineName("EmailImporter-${account.id}"),
                    )

                    newImporter.start()

                    importer[account.id] = newImporter
                }
            }
    }
}
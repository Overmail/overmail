package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.domain.repository.EmailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailUserRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepositoryImpl
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val config = DatabaseConfig()
        val db = OvermailDatabase(config)
        db.init()

        coroutineScope {
            val changeStream = PostgresChangeStream(config, this)
            val imapAccountRepository = ImapAccountRepositoryImpl(db, changeStream)
            val emailUserRepository = EmailUserRepositoryImpl(db, changeStream)
            val emailRepository = EmailRepositoryImpl(db, changeStream)

            val emailImporterManager = ImporterManager(
                database = db,
                imapAccountRepository = imapAccountRepository,
                emailUserRepository = emailUserRepository,
                emailRepository = emailRepository,
                coroutineScope = this,
            )

            emailImporterManager.start()
        }
    }
}

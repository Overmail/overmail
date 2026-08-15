package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepository
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.UserRepository
import es.jvbabi.overmail.server.domain.repository.UserRepositoryImpl
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.launch

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * that the change stream and the importers run in, so stopping the server tears both down.
 */
fun Application.overmail() {
    configureDependencies()
    configureRouting()
    startImporter()
}

private fun Application.configureDependencies() {
    dependencies {
        provide<DatabaseConfig> { DatabaseConfig() }

        // Creating the schema on first resolution keeps it in one place; the providers below are
        // the only way to reach the database, so nothing can query it before this ran.
        provide<OvermailDatabase> { OvermailDatabase(resolve()).also { it.init() } }

        provide<PostgresChangeStream> { PostgresChangeStream(resolve(), this@configureDependencies) }

        provide<UserRepository> { UserRepositoryImpl(resolve(), resolve()) }
        provide<ImapAccountRepository> { ImapAccountRepositoryImpl(resolve(), resolve()) }
        provide<EmailUserRepository> { EmailUserRepositoryImpl(resolve(), resolve()) }
        provide<EmailRepository> { EmailRepositoryImpl(resolve(), resolve()) }

        provide<ImporterManager> {
            ImporterManager(
                database = resolve(),
                imapAccountRepository = resolve(),
                emailUserRepository = resolve(),
                emailRepository = resolve(),
                coroutineScope = this@configureDependencies,
            )
        }
    }
}

private fun Application.startImporter() {
    launch {
        dependencies.resolve<ImporterManager>().start()
    }
}

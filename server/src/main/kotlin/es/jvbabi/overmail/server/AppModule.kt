package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.installOvermailAuthentikt
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.data.ChangeNotifiers
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.EmailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepository
import es.jvbabi.overmail.server.domain.repository.ImapAccountRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.OutgoingMailRepository
import es.jvbabi.overmail.server.domain.repository.OutgoingMailRepositoryImpl
import es.jvbabi.overmail.server.domain.repository.UserRepository
import es.jvbabi.overmail.server.domain.repository.UserRepositoryImpl
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.launch

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * the importers run in, so stopping the server tears them down.
 */
fun Application.overmail() {
    configureDependencies()
    // Authentikt receives typed request bodies, so this has to be in place before its routes are.
    install(ContentNegotiation) { json() }
    installOvermailAuthentikt()
    configureRouting()
    startImporter()
}

private fun Application.configureDependencies() {
    dependencies {
        provide<ApplicationConfig> { ApplicationConfig.load() }
        provide<DatabaseConfig> { resolve<ApplicationConfig>().database }
        provide<SmtpConfig> { resolve<ApplicationConfig>().email.smtp }

        // Creating the schema on first resolution keeps it in one place; the providers below are
        // the only way to reach the database, so nothing can query it before this ran.
        provide<OvermailDatabase> { OvermailDatabase(resolve()).also { it.init() } }

        // Not tied to a connection or a scope: the notifiers only pass changes from the
        // repository that wrote them to the flows that have to reload because of it.
        provide<ChangeNotifiers> { ChangeNotifiers() }

        provide<UserRepository> { UserRepositoryImpl(resolve(), resolve()) }
        provide<ImapAccountRepository> { ImapAccountRepositoryImpl(resolve(), resolve()) }
        provide<EmailUserRepository> { EmailUserRepositoryImpl(resolve(), resolve()) }
        provide<EmailRepository> { EmailRepositoryImpl(resolve(), resolve()) }
        provide<OutgoingMailRepository> { OutgoingMailRepositoryImpl(resolve()) }
        provide<JwtService> { JwtService() }

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

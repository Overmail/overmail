package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.auth.installOvermailAuthentikt
import es.jvbabi.overmail.server.auth.overmailSession
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.http.configureRouting
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.di.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor application is the composition root: it owns the object graph and the coroutine scope
 * the importers run in, so stopping the server tears them down.
 */
fun Application.overmail() {
    configureDependencies()
    // Authentikt receives typed request bodies, so this has to be in place before its routes are.
    install(ContentNegotiation) { json() }
    install(Authentication) { overmailSession() }
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    installOvermailAuthentikt()
    configureRouting()
    startImporter()
}

private fun Application.configureDependencies() {
    dependencies {
        provide<ApplicationConfig> { ApplicationConfig.load() }
        provide<DatabaseConfig> { resolve<ApplicationConfig>().database }
        provide<SmtpConfig> { resolve<ApplicationConfig>().email.smtp }

        // Creating the schema on first resolution keeps it in one place: every caller reaches
        // the database through this provider, so nothing can query it before this ran.
        provide<OvermailDatabase> { OvermailDatabase(resolve<DatabaseConfig>()).also { it.init() } }

        provide<JwtService> { JwtService() }

        provide<ImporterManager> {
            ImporterManager(
                database = resolve(),
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

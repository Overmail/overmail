package es.jvbabi.overmail.server

import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.auth.JwtService
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
import es.jvbabi.overmail.server.jobs.avatar.AvatarShapeBackfill
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import es.jvbabi.overmail.server.jobs.preview.EmailPreviewQueue
import es.jvbabi.overmail.server.oauth.OAuthProviders
import es.jvbabi.overmail.server.oauth.OAuthTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

private val CONFIG = Json.decodeFromString<ApplicationConfig>(
    """
    {
      "base_url": "https://overmail.example",
      "database": {"host": "localhost", "database": "overmail", "username": "overmail", "password": "none"},
      "email": {"smtp": {"host": "smtp.example", "port": 465, "auth": {"username": "overmail", "password": "none"}}},
      "ai": {"api_key": "none", "model": "a-model", "base_url": "http://localhost:1"}
    }
    """
)

/**
 * The module the server runs on. The routes' tests wire a module of their own, so a definition
 * missing from this one would otherwise only show up when the server starts.
 */
class AppModuleTest {

    @Test
    fun `everything the application asks for can be built`() {
        val scope = CoroutineScope(Dispatchers.Default)
        // What the real module reads from disk or connects to, replaced; everything else is its own.
        val replaced = module {
            single<ApplicationConfig> { CONFIG }
            single<OvermailDatabase> {
                OvermailDatabase(Database.connect("jdbc:h2:mem:app-module-${Uuid.random()};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver"))
            }
            single<JwtService> { JwtService(Files.createTempDirectory("overmail-jwt").toString()) }
        }
        val koin = koinApplication {
            allowOverride(true)
            modules(overmailModule(scope), replaced)
        }.koin

        try {
            // What `startJobs` and the routes resolve; between them they reach every definition.
            koin.get<ImporterManager>()
            koin.get<OAuthTokens>()
            koin.get<OAuthProviders>()
            koin.get<EmailClassificationQueue>()
            koin.get<ChatAgentQueue>()
            koin.get<AvatarQueue>()
            koin.get<AvatarShapeBackfill>()
            koin.get<EmailPreviewQueue>()
            koin.get<ViewNotifier>()
            koin.get<JwtService>()
            koin.get<SmtpConfig>()
            assertEquals("a-model", koin.get<LLModel>().id)
        } finally {
            koin.close()
            scope.cancel()
        }
    }
}

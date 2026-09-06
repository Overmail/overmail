package es.jvbabi.overmail.server.http.users.me.inboxes.item

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.EmailConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

class SetInboxPausedTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:pause-inbox;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `pausing and resuming flip the row and nothing else`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        // A fresh mailbox runs.
        assertFalse(database.query { ImapAccount.findById(account)!!.isPaused })

        val paused = client.post("/api/users/me/inboxes/$account/pause")
        assertEquals(HttpStatusCode.OK, paused.status)
        assertTrue(
            Json.parseToJsonElement(paused.bodyAsText()).jsonObject["paused"]!!.jsonPrimitive.content.toBoolean(),
        )
        assertTrue(database.query { ImapAccount.findById(account)!!.isPaused })

        // Nothing about the account itself changes; that is the point of pausing.
        database.query {
            val row = ImapAccount.findById(account)!!
            assertEquals("imap.example.com", row.host)
            assertEquals(993, row.port)
        }

        val resumed = client.post("/api/users/me/inboxes/$account/resume")
        assertEquals(HttpStatusCode.OK, resumed.status)
        assertFalse(database.query { ImapAccount.findById(account)!!.isPaused })
    }

    @Test
    fun `pausing twice is not an error`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        assertEquals(HttpStatusCode.OK, client.post("/api/users/me/inboxes/$account/pause").status)
        assertEquals(HttpStatusCode.OK, client.post("/api/users/me/inboxes/$account/pause").status)
        assertTrue(database.query { ImapAccount.findById(account)!!.isPaused })
    }

    @Test
    fun `somebody else's mailbox cannot be paused, and is a miss`() = testApplication {
        setUpUser()
        installRoute()

        val strangers = database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Someone"
                lastname = "Else"
            }
            ImapAccount.new {
                user = stranger
                host = "imap.stranger.example"
                port = 993
                username = "stranger@example.com"
                password = "secret"
            }.id.value
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/users/me/inboxes/$strangers/pause").status)
        assertFalse(database.query { ImapAccount.findById(strangers)!!.isPaused })
    }

    @Test
    fun `without a session nothing is paused`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)
        signedIn = null

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/users/me/inboxes/$account/pause").status)
        signedIn = user
        assertFalse(database.query { ImapAccount.findById(account)!!.isPaused })
    }

    private suspend fun setUpAccount(user: User): Uuid = database.query {
        ImapAccount.new {
            this.user = user
            host = "imap.example.com"
            port = 993
            username = "julius-${Uuid.random()}@example.com"
            password = "secret"
        }.id.value
    }

    private suspend fun setUpUser(): User {
        database.init()
        return database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
        }.also { signedIn = it }
    }

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    /** Nothing in here is reached: the manager runs on a scope that is already over. */
    private val testConfig = ApplicationConfig(
        baseUrl = "http://localhost",
        database = DatabaseConfig(host = "localhost", database = "none", user = "none", password = "none"),
        email = EmailConfig(
            smtp = SmtpConfig(host = "localhost", port = 25, auth = SmtpConfig.Auth(username = "none", password = "none"))
        ),
        ai = ApplicationConfig.AiConfig(apiKey = "none", model = "test-model", baseUrl = "http://localhost:1"),
    )

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<ImporterManager> {
                    ImporterManager(
                        database = database,
                        coroutineScope = CoroutineScope(Job()).also { it.cancel() },
                        emailClassificationQueue = EmailClassificationQueue(
                            emailClassification = EmailClassification(
                                config = testConfig,
                                model = testModel,
                                overmailDatabase = database,
                                mailNotifier = MailNotifier(),
                                knowledgeStore = KnowledgeStore(database),
                            ),
                            database = database,
                        ),
                        mailNotifier = MailNotifier(),
                    )
                }
            }
            routing {
                route("/api/users/me/inboxes/{inboxId}") {
                    route("/pause") { setInboxPaused(paused = true) }
                    route("/resume") { setInboxPaused(paused = false) }
                }
            }
        }
    }

    private fun AuthenticationConfig.session() =
        register(object : AuthenticationProvider(TestConfig()) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                val user = signedIn
                if (user == null) {
                    context.challenge("test", AuthenticationFailedCause.NoCredentials) { challenge, call ->
                        call.respond(HttpStatusCode.Unauthorized)
                        challenge.complete()
                    }
                    return
                }
                context.principal(user)
            }
        })

    private class TestConfig : AuthenticationProvider.Config(null)
}

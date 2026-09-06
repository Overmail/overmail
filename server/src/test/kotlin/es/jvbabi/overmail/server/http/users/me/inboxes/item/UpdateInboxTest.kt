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
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSyncs
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

class UpdateInboxTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:update-inbox;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    /** Nothing listens here, so the imap lookup for a count fails fast instead of reaching anyone. */
    private val deadPort = ServerSocket(0).use { it.localPort }

    @Test
    fun `the detail carries the folder settings the screen has to open on`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        val response = client.get("/api/users/me/inboxes/$account")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("julius@example.com", body["username"]!!.jsonPrimitive.content)
        // The password is not in it, here as anywhere.
        assertFalse(response.bodyAsText().contains("secret"), response.bodyAsText())

        val folders = body["folders"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Archiv", "INBOX"), folders.map { it["folder_name"]!!.jsonPrimitive.content })

        val inbox = folders.single { it["folder_name"]!!.jsonPrimitive.content == "INBOX" }
        assertEquals(true, inbox["imap_push"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("only_new_messages", inbox["ai_import"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val archive = folders.single { it["folder_name"]!!.jsonPrimitive.content == "Archiv" }
        assertEquals("after_date", archive["ai_import"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1700000000, archive["ai_import"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `an empty password keeps the stored one`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        val response = client.put("/api/users/me/inboxes/$account") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"imap":{"host":"imap.moved.example","port":$deadPort,"username":"moved@example.com","password":""},
                 "folder_settings":[{"folder_name":"INBOX","imap_push":false,"ai_import":{"type":"all_messages"}}]}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        database.query {
            val row = ImapAccount.findById(account)!!
            assertEquals("imap.moved.example", row.host)
            assertEquals("moved@example.com", row.username)
            // The one field the screen never sees, and therefore never sends back.
            assertEquals("secret", row.password)
        }
    }

    @Test
    fun `a password that was typed replaces the stored one`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        client.put("/api/users/me/inboxes/$account") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"imap":{"host":"imap.example.com","port":993,"username":"julius@example.com","password":"a-new-one"},
                 "folder_settings":[{"folder_name":"INBOX","imap_push":false,"ai_import":{"type":"all_messages"}}]}
                """.trimIndent()
            )
        }

        assertEquals("a-new-one", database.query { ImapAccount.findById(account)!!.password })
    }

    @Test
    fun `the folder rows are replaced by exactly what was sent`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)

        client.put("/api/users/me/inboxes/$account") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"imap":{"host":"imap.example.com","port":993,"username":"julius@example.com","password":""},
                 "folder_settings":[
                   {"folder_name":"Sent","imap_push":true,"ai_import":{"type":"only_new_messages"}}]}
                """.trimIndent()
            )
        }

        val stored = database.query {
            ImapAccountFolderSyncs
                .selectAll()
                .where { ImapAccountFolderSyncs.imapAccount eq account }
                .associate { it[ImapAccountFolderSyncs.folder] to it[ImapAccountFolderSyncs.imapPush] }
        }
        // Archiv and INBOX were taken out of the screen, so they are gone from the account.
        assertEquals(mapOf("Sent" to true), stored)
    }

    @Test
    fun `moving onto a mailbox this user already has is refused`() = testApplication {
        val user = setUpUser()
        installRoute()
        val account = setUpAccount(user)
        val other = database.query {
            ImapAccount.new {
                this.user = user
                host = "imap.other.example"
                port = 993
                username = "other@example.com"
                password = "secret"
            }.id.value
        }

        // Two importers on one mailbox would write every mail twice.
        val response = client.put("/api/users/me/inboxes/$other") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"imap":{"host":"imap.example.com","port":993,"username":"julius@example.com","password":""},
                 "folder_settings":[{"folder_name":"INBOX","imap_push":false,"ai_import":{"type":"all_messages"}}]}
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("imap.other.example", database.query { ImapAccount.findById(other)!!.host })
        assertEquals("imap.example.com", database.query { ImapAccount.findById(account)!!.host })
    }

    @Test
    fun `somebody else's mailbox is a miss, for reading and for writing`() = testApplication {
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

        assertEquals(HttpStatusCode.NotFound, client.get("/api/users/me/inboxes/$strangers").status)

        val write = client.put("/api/users/me/inboxes/$strangers") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"imap":{"host":"imap.taken.example","port":993,"username":"taken","password":""},
                 "folder_settings":[{"folder_name":"INBOX","imap_push":false,"ai_import":{"type":"all_messages"}}]}
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.NotFound, write.status)
        assertEquals("imap.stranger.example", database.query { ImapAccount.findById(strangers)!!.host })
    }

    private suspend fun setUpAccount(user: User): Uuid = database.query {
        val account = ImapAccount.new {
            this.user = user
            host = "imap.example.com"
            port = 993
            username = "julius@example.com"
            password = "secret"
        }
        ImapAccountFolderSync.new {
            imapAccount = account
            folder = "INBOX"
            imapPush = true
            aiImport = ImapAccountFolderSync.AiImportSettings.OnlyNewMessages
        }
        ImapAccountFolderSync.new {
            imapAccount = account
            folder = "Archiv"
            imapPush = false
            aiImport = ImapAccountFolderSync.AiImportSettings.AfterDate(kotlin.time.Instant.fromEpochSeconds(1700000000))
        }
        account.id.value
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
                    getInbox()
                    updateInbox()
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

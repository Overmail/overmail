package es.jvbabi.overmail.server.http.users.me.inboxes.create.submit

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
import io.ktor.client.request.post
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

private const val ROUTE = "/api/users/me/inboxes/create/submit"

class SubmitInboxTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:submit-inbox;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    /** A port nothing listens on, so the imap lookups fail fast instead of reaching anybody. */
    private val deadPort = ServerSocket(0).use { it.localPort }

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    /** Nothing in here is reached: the importer this feeds runs on a scope that is already over. */
    private val testConfig = ApplicationConfig(
        baseUrl = "http://localhost",
        database = DatabaseConfig(host = "localhost", database = "none", user = "none", password = "none"),
        email = EmailConfig(
            smtp = SmtpConfig(
                host = "localhost",
                port = 25,
                auth = SmtpConfig.Auth(username = "none", password = "none"),
            )
        ),
        ai = ApplicationConfig.AiConfig(apiKey = "none", model = "test-model", baseUrl = "http://localhost:1"),
    )

    private fun body(folders: String, host: String = "127.0.0.1") = """
        {"imap":{"host":"$host","port":$deadPort,"username":"julius","password":"secret"},
         "folder_settings":[$folders]}
    """.trimIndent()

    private fun folder(name: String, push: Boolean, scope: String) =
        """{"folder_name":"$name","imap_push":$push,"ai_import":$scope}"""

    @Test
    fun `an inbox without folders is refused`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(body(folders = ""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("folder_settings", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the same folder cannot be configured twice`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(
                body(
                    folders = folder("INBOX", true, """{"type":"all_messages"}""") + "," +
                        folder("INBOX", false, """{"type":"only_new_messages"}"""),
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("INBOX", error["details"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every folder is stored with the settings it was sent with`() = testApplication {
        val user = setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(
                body(
                    folders = folder("INBOX", true, """{"type":"only_new_messages"}""") + "," +
                        folder("Archiv", false, """{"type":"all_messages"}""") + "," +
                        folder("Sent", false, """{"type":"after_date","timestamp":1700000000}"""),
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val id = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val stored = database.query {
            val account = ImapAccount.findById(Uuid.parse(id))!!
            assertEquals(user.id, account.user.id)
            ImapAccountFolderSyncs
                .selectAll()
                .where { ImapAccountFolderSyncs.imapAccount eq account.id }
                .associate { it[ImapAccountFolderSyncs.folder] to (it[ImapAccountFolderSyncs.imapPush] to it[ImapAccountFolderSyncs.aiImport]) }
        }

        assertEquals(
            true to ImapAccountFolderSync.AiImportSettings.OnlyNewMessages,
            stored.getValue("INBOX"),
        )
        assertEquals(
            false to ImapAccountFolderSync.AiImportSettings.AllMessages,
            stored.getValue("Archiv"),
        )
        assertEquals(
            ImapAccountFolderSync.AiImportSettings.AfterDate(kotlin.time.Instant.fromEpochSeconds(1700000000)),
            stored.getValue("Sent").second,
        )
    }

    @Test
    fun `a count that cannot be resolved becomes the whole folder, not a failed submit`() = testApplication {
        setUpUser()
        installRoute()

        // The mailbox is unreachable, so there is no n-th newest mail to date the boundary from.
        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(body(folders = folder("INBOX", false, """{"type":"newest_messages","count":500}""")))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val id = Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val scope = database.query {
            ImapAccountFolderSyncs
                .selectAll()
                .where { ImapAccountFolderSyncs.imapAccount eq Uuid.parse(id) }
                .single()[ImapAccountFolderSyncs.aiImport]
        }
        // More is read than was asked for, never less -- the account is still worth having.
        assertEquals(ImapAccountFolderSync.AiImportSettings.AllMessages, scope)
    }

    @Test
    fun `the same mailbox cannot be added twice`() = testApplication {
        setUpUser()
        installRoute()

        val payload = body(folders = folder("INBOX", false, """{"type":"all_messages"}"""))
        val first = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        val error = Json.parseToJsonElement(second.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("conflict", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a session nothing is created`() = testApplication {
        database.init()
        signedIn = null
        installRoute()

        // Counted rather than asserted empty: every test in this class shares one in-memory
        // database, so what the others left behind is not this test's business.
        val before = database.query { ImapAccount.all().count() }

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody(body(folders = folder("INBOX", false, """{"type":"all_messages"}""")))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(before, database.query { ImapAccount.all().count() })
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

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { session() }
            dependencies {
                provide<OvermailDatabase> { database }
                // A manager on a scope that is already over: the route reboots the importer after
                // answering, and a test has no mailbox for it to connect to.
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
                route(ROUTE) { inboxSubmitRoute() }
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

package es.jvbabi.overmail.server.http.email.upload

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.jobs.importer.ClassifyStep
import es.jvbabi.overmail.server.jobs.importer.EmailImportPipeline
import es.jvbabi.overmail.server.jobs.importer.EmailImportPipelineImpl
import es.jvbabi.overmail.server.jobs.importer.EmailInserterImpl
import es.jvbabi.overmail.server.jobs.importer.PushStep
import es.jvbabi.overmail.server.jobs.importer.testDatabase
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A mail handed in as an `.eml` file, stored like an imported one. */
class ImportEmailTest {

    private val database = testDatabase("import-email")

    private lateinit var signedIn: User
    private lateinit var inbox: Uuid
    private lateinit var strangersInbox: Uuid

    private val classified = mutableListOf<Uuid>()
    private val pushed = mutableListOf<Uuid>()

    private val source = """
        From: Ada Lovelace <ada@example.com>
        To: Julius <julius@example.com>
        Subject: Rechnung ${Uuid.random()}
        Date: Mon, 1 Jun 2026 12:00:00 +0000
        Message-ID: <rechnung@example.com>
        Content-Type: text/plain; charset=utf-8

        Hallo.
    """.trimIndent().replace("\n", "\r\n").toByteArray()

    @Test
    fun `the mail is stored in the inbox and goes to the assistant, but is not pushed`() = testApplication {
        setUp()
        installRoute()

        val response = upload(source)

        assertEquals(HttpStatusCode.Created, response.status)
        val id = Uuid.parse(Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
        database.query {
            val email = Email[id]
            assertEquals(inbox, email.imapAccount.id.value)
            assertContentEquals(source, email.rawContent)
            assertEquals("ada@example.com", email.sender.address)
        }
        assertEquals(listOf(id), classified)
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `the same mail twice is a conflict`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.Created, upload(source).status)
        assertEquals(HttpStatusCode.Conflict, upload(source).status)
    }

    @Test
    fun `a body that cannot be stored is refused`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, upload(ByteArray(0)).status)
        // No From and no Date: nothing a stored mail could be filed under.
        assertEquals(HttpStatusCode.BadRequest, upload("Subject: Nothing\r\n\r\nHallo.\r\n".toByteArray()).status)
    }

    @Test
    fun `the parameters are checked`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, upload(source, type = "mbox").status)
        assertEquals(HttpStatusCode.BadRequest, upload(source, type = null).status)
        assertEquals(HttpStatusCode.BadRequest, upload(source, inboxId = null).status)
        assertEquals(HttpStatusCode.BadRequest, upload(source, inboxId = "not-an-id").status)
    }

    @Test
    fun `an inbox that is not the caller's is not found`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.NotFound, upload(source, inboxId = strangersInbox.toString()).status)
        assertEquals(HttpStatusCode.NotFound, upload(source, inboxId = Uuid.random().toString()).status)
    }

    private suspend fun ApplicationTestBuilder.upload(
        body: ByteArray,
        type: String? = "eml",
        inboxId: String? = inbox.toString(),
    ) = client.post("/api/emails/import") {
        type?.let { parameter("type", it) }
        inboxId?.let { parameter("target_inbox_id", it) }
        contentType(ContentType("message", "rfc822"))
        setBody(body)
    }

    /** A signed-in user with one inbox, plus somebody else's inbox to be refused. */
    private suspend fun setUp() {
        database.init()
        database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }
            inbox = ImapAccount.new {
                user = signedIn
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }.id.value
            strangersInbox = ImapAccount.new {
                user = stranger
                host = "imap.example.com"
                port = 993
                username = "stranger"
                password = "secret"
            }.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            install(Koin) {
                modules(module {
                    single<OvermailDatabase> { database }
                    single<EmailImportPipeline> {
                        EmailImportPipelineImpl(
                            inserter = EmailInserterImpl(database),
                            steps = listOf(
                                ClassifyStep(enqueue = { classified += it }),
                                PushStep(push = { _, emailId, _ -> pushed += emailId }),
                            ),
                        )
                    }
                })
            }
            routing {
                route("/api/emails/import") { importEmail() }
            }
        }
    }

    private fun AuthenticationConfig.alwaysSignedIn() =
        register(object : AuthenticationProvider(TestConfig()) {
            override suspend fun onAuthenticate(context: AuthenticationContext) {
                context.principal(signedIn)
            }
        })

    private class TestConfig : AuthenticationProvider.Config(null)
}

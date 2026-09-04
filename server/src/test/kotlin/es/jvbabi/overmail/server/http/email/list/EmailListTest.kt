package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** The mailbox by position: what a windowed table asks for a slice of. */
class EmailListTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-list;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `answers a slice, newest first, with the length of the whole list`() = testApplication {
        val mails = setUp(count = 5)
        installRoute()

        val page = client.get("/api/emails/list?offset=1&limit=2").page()

        assertEquals(5, page["total"]!!.jsonPrimitive.long)
        assertEquals(1, page["offset"]!!.jsonPrimitive.long)
        assertEquals(mails.subList(1, 3).map { it.toString() }, page.ids())
    }

    @Test
    fun `spam is left out, archived mails are not`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list").page()

        assertEquals(2, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[1], mails[2]).map { it.toString() }, page.ids())
    }

    @Test
    fun `a mail of another user is not in the list`() = testApplication {
        setUp(count = 1)
        installRoute()
        database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }
            val account = ImapAccount.new {
                user = stranger
                host = "imap.example.com"
                port = 993
                username = "stranger"
                password = "secret"
            }
            val sender = EmailUser.new {
                user = stranger
                address = "someone@example.com"
            }
            Email.new {
                imapAccount = account
                this.sender = sender
                senderName = null
                subject = "Not yours"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
            }
        }

        assertEquals(1, client.get("/api/emails/list").page()["total"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a nonsense range is answered with what there is`() = testApplication {
        setUp(count = 2)
        installRoute()

        // Past the end: an empty page rather than an error, the length still reported.
        val beyond = client.get("/api/emails/list?offset=99&limit=5000").page()
        assertEquals(2, beyond["total"]!!.jsonPrimitive.long)
        assertEquals(0, beyond.ids().size)

        // A negative offset is the start of the list, and the limit is clamped into what one
        // request may ask for.
        val clamped = client.get("/api/emails/list?offset=-5&limit=0").page()
        assertEquals(0, clamped["offset"]!!.jsonPrimitive.long)
        assertEquals(1, clamped.ids().size)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.page() =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.ids() = page().ids()

    private fun kotlinx.serialization.json.JsonObject.ids() =
        getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun archive(emailId: Uuid, action: EmailArchiveAction) {
        database.query {
            EmailArchive.new {
                email = Email.findById(emailId)!!
                this.action = action
                createdAt = Clock.System.now()
                createdByAgent = false
            }
        }
    }

    /** [count] mails, newest first in the returned list. */
    private suspend fun setUp(count: Int): List<Uuid> {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val account = ImapAccount.new {
                user = signedIn
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                user = signedIn
                address = "sender@example.com"
            }
            val now = Clock.System.now()

            (0 until count).map { index ->
                Email.new {
                    imapAccount = account
                    this.sender = sender
                    senderName = "The Sender"
                    subject = "Mail $index"
                    sent = now - index.days
                    rawContent = ByteArray(0)
                }.id.value
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) { alwaysSignedIn() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/emails/list") { emailList() }
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

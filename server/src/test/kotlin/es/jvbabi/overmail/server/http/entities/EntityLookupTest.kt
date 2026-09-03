package es.jvbabi.overmail.server.http.entities

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.email.emailsByIds
import es.jvbabi.overmail.server.http.labels.labelsByIds
import es.jvbabi.overmail.server.http.senders.sendersByIds
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
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The lookups a client-side cache runs against: it asks for the ids it does not know and takes
 * what comes back. What is under test is that the answer never leaves the signed-in user's data.
 */
class EntityLookupTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:entity-lookup;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `looks up emails, labels and senders by id`() = testApplication {
        val fixture = setUp()
        installRoutes()

        val email = client.get("/api/emails?ids=${fixture.emailId},${Uuid.random()}")
            .entities("emails").single().jsonObject
        assertEquals("Invoice 42", email["subject"]!!.jsonPrimitive.content)
        assertEquals(fixture.senderId.toString(), email["sender_id"]!!.jsonPrimitive.content)
        assertEquals("The Sender", email["sender_name"]!!.jsonPrimitive.content)

        val label = client.get("/api/labels?ids=${fixture.labelId}").entities("labels").single().jsonObject
        assertEquals("Studium", label["name"]!!.jsonPrimitive.content)
        assertEquals("#ffffff", label["color"]!!.jsonPrimitive.content)

        val sender = client.get("/api/senders?ids=${fixture.senderId}").entities("senders").single().jsonObject
        assertEquals("sender@example.com", sender["address"]!!.jsonPrimitive.content)
        // From the newest mail of that address, not from the address row.
        assertEquals("The Sender", sender["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `answers nothing for another user's ids`() = testApplication {
        val fixture = setUp()
        installRoutes()
        signedIn = database.query {
            User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
        }

        assertEquals(0, client.get("/api/emails?ids=${fixture.emailId}").entities("emails").size)
        assertEquals(0, client.get("/api/labels?ids=${fixture.labelId}").entities("labels").size)
        assertEquals(0, client.get("/api/senders?ids=${fixture.senderId}").entities("senders").size)
    }

    @Test
    fun `an empty or unparseable id list is an empty answer`() = testApplication {
        setUp()
        installRoutes()

        assertEquals(0, client.get("/api/emails").entities("emails").size)
        assertEquals(0, client.get("/api/emails?ids=").entities("emails").size)
        assertEquals(0, client.get("/api/emails?ids=not-a-uuid").entities("emails").size)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.entities(key: String) =
        Json.parseToJsonElement(bodyAsText()).jsonObject[key]!!.jsonArray

    private data class Fixture(val emailId: Uuid, val labelId: Uuid, val senderId: Uuid)

    private suspend fun setUp(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
            signedIn = user

            val account = ImapAccount.new {
                this.user = user
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                this.user = user
                address = "sender@example.com"
            }
            val email = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Invoice 42"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                textContent = "Please pay."
            }
            val label = Label.new {
                name = "Studium"
                color = "#ffffff"
                owner = user
                createdAt = Clock.System.now()
                createdByAgent = false
            }

            Fixture(emailId = email.id.value, labelId = label.id.value, senderId = sender.id.value)
        }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) { alwaysSignedIn() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/emails") { emailsByIds() }
                route("/api/labels") { labelsByIds() }
                route("/api/senders") { sendersByIds() }
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

package es.jvbabi.overmail.server.http.email.item.labels

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.labels.createLabel
import io.ktor.client.request.delete
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
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The labels a reader hangs on a mail, and the ones they make while doing it. */
class EmailLabelsTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-labels;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var stranger: User

    @Test
    fun `a label goes on and comes off again`() = testApplication {
        val (mail, label) = setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.NoContent, client.post("/api/emails/$mail/labels/$label").status)
        assertTrue(attached(mail, label))

        // Twice is once: the pair is unique, and the second request is the state the caller wants.
        assertEquals(HttpStatusCode.NoContent, client.post("/api/emails/$mail/labels/$label").status)
        assertEquals(1, assignments(mail, label))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/emails/$mail/labels/$label").status)
        assertTrue(!attached(mail, label))
        // And off an unlabelled mail as well, which is where it already is.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/emails/$mail/labels/$label").status)
    }

    @Test
    fun `neither a mail nor a label of somebody else is touched`() = testApplication {
        val (mail, label) = setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        val foreign = database.query {
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
            val mailOfTheirs = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = null
                subject = "Not yours"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
            }.id.value
            val labelOfTheirs = Label.new {
                name = "Privat"
                color = "#ffffff"
                owner = stranger
                createdByAgent = false
            }.id.value

            mailOfTheirs to labelOfTheirs
        }

        // Somebody else's is a 403, something that is not there at all a 404.
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/emails/${foreign.first}/labels/$label").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/emails/$mail/labels/${foreign.second}").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/emails/$mail/labels/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/emails/${foreign.first}/labels/$label").status)
        assertTrue(!attached(foreign.first, label))
    }

    @Test
    fun `a new label is made and hung on the mails of the request`() = testApplication {
        val (mail, _) = setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        val response = client.post("/api/labels") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  Uni   Kram ","attach_to_email_ids":["$mail"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val label = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Normalized on the way in, and a colour comes back even though none went out.
        assertEquals("Uni Kram", label["name"]!!.jsonPrimitive.content)
        assertTrue(Regex("^#[0-9A-Fa-f]{6}$").matches(label["color"]!!.jsonPrimitive.content))
        assertTrue(attached(mail, Uuid.parse(label["id"]!!.jsonPrimitive.content)))
    }

    @Test
    fun `a name this user already has is that label, not a second one`() = testApplication {
        val (mail, existing) = setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        // Different casing and spacing, and the label that is already there answers -- with the
        // spelling it was stored under.
        val response = client.post("/api/labels") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"studium","attach_to_email_ids":["$mail"]}""")
        }

        val label = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(existing.toString(), label["id"]!!.jsonPrimitive.content)
        assertEquals("Studium", label["name"]!!.jsonPrimitive.content)
        assertEquals(1, database.query { Labels.selectAll().where { Labels.owner eq signedIn.id }.count() })
        assertTrue(attached(mail, existing))
    }

    @Test
    fun `a colour has to be one, and a name has to be there`() = testApplication {
        setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        val blank = client.post("/api/labels") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)

        // Anything but #rrggbb, because it ends up in a stylesheet.
        val paint = client.post("/api/labels") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Rechnungen","color":"red; content: bad"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, paint.status)
    }

    @Test
    fun `a mail of somebody else is not labelled by asking for it in a create`() = testApplication {
        setUp()
        installRoute()
        val client = createClient { install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() } }

        val foreignMail = database.query {
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
            }.id.value
        }

        val response = client.post("/api/labels") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Neu","attach_to_email_ids":["$foreignMail"]}""")
        }

        // The label is made -- it is this user's -- and nothing was hung on a mail of theirs.
        assertEquals(HttpStatusCode.OK, response.status)
        val label = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(!attached(foreignMail, Uuid.parse(label["id"]!!.jsonPrimitive.content)))
    }

    private suspend fun attached(emailId: Uuid, labelId: Uuid): Boolean =
        assignments(emailId, labelId) > 0

    private suspend fun assignments(emailId: Uuid, labelId: Uuid): Int = database.query {
        EmailLabel.find { (EmailLabels.email eq emailId) and (EmailLabels.label eq labelId) }.count().toInt()
    }

    /** A user with a mail and a label of their own, plus somebody else. */
    private suspend fun setUp(): Pair<Uuid, Uuid> {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
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
            val mail = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Mail"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
            }.id.value
            val label = Label.new {
                name = "Studium"
                color = "#eeeeff"
                owner = signedIn
                createdByAgent = false
            }.id.value

            mail to label
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<MailNotifier> { MailNotifier() }
            }
            routing {
                route("/api/emails/{emailId}/labels/{labelId}") {
                    attachEmailLabel()
                    detachEmailLabel()
                }
                route("/api/labels") { createLabel() }
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

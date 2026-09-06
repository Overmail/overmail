package es.jvbabi.overmail.server.http.share

import es.jvbabi.overmail.server.data.share.SharePassword
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Share
import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll

/** What a share link hands out to somebody who has no account here. */
class ShareViewTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:share-view;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var owner: User
    private lateinit var mail: Email

    @Test
    fun `a link without a password hands out the whole mail, hyphens or not`() = testApplication {
        val share = setUp { }
        installRoutes()

        val response = client.get("/api/shares/$share")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["needs_password"]!!.jsonPrimitive.content.toBoolean())

        val metadata = body["metadata"]!!.jsonObject
        assertEquals("Die Rechnung", metadata["subject"]!!.jsonPrimitive.content)
        assertEquals("sender@example.com", metadata["sender_address"]!!.jsonPrimitive.content)
        assertEquals("The Sender", metadata["sender_name"]!!.jsonPrimitive.content)
        assertEquals("<p>Hallo</p>", body["content"]!!.jsonObject["html"]!!.jsonPrimitive.content)

        // The url a reader sees carries the id without its hyphens, so that has to answer too.
        val bare = client.get("/api/shares/${share.toString().replace("-", "")}")
        assertEquals(HttpStatusCode.OK, bare.status)
    }

    @Test
    fun `labels come with the mail only where the share was made with them`() = testApplication {
        val withLabels = setUp { includeLabels = true }
        installRoutes()

        val labels = Json.parseToJsonElement(client.get("/api/shares/$withLabels").bodyAsText())
            .jsonObject["metadata"]!!.jsonObject["labels"]!!.jsonArray
        assertEquals(1, labels.size)
        assertEquals("Rechnungen", labels.single().jsonObject["name"]!!.jsonPrimitive.content)

        val without = database.query {
            Share.new {
                email = mail
                sharedAt = Clock.System.now()
                includeLabels = false
            }.id.value
        }
        val none = Json.parseToJsonElement(client.get("/api/shares/$without").bodyAsText())
            .jsonObject["metadata"]!!.jsonObject["labels"]!!.jsonArray
        assertTrue(none.isEmpty())
    }

    @Test
    fun `a password keeps the mail back, and the right one hands it over`() = testApplication {
        val share = setUp {
            passwordHash = SharePassword.hash("hunter2")
            allowMetadataWithoutPassword = true
        }
        installRoutes()

        val locked = client.get("/api/shares/$share")
        val lockedBody = Json.parseToJsonElement(locked.bodyAsText()).jsonObject
        assertTrue(lockedBody["needs_password"]!!.jsonPrimitive.content.toBoolean())
        // Subject and sender, because this share allows them -- but never the mail itself, which
        // would be in the response for anyone to read without typing anything.
        assertEquals("Die Rechnung", lockedBody["metadata"]!!.jsonObject["subject"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, lockedBody["content"])
        assertFalse(locked.bodyAsText().contains("Hallo"))

        val wrong = client.post("/api/shares/$share/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"password": "hunter3"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertFalse(wrong.bodyAsText().contains("Hallo"))

        val opened = client.post("/api/shares/$share/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"password": "hunter2"}""")
        }
        assertEquals(HttpStatusCode.OK, opened.status)
        val openedBody = Json.parseToJsonElement(opened.bodyAsText()).jsonObject
        assertFalse(openedBody["needs_password"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("<p>Hallo</p>", openedBody["content"]!!.jsonObject["html"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a share that hides its metadata says nothing but that a password is needed`() = testApplication {
        val share = setUp {
            passwordHash = SharePassword.hash("hunter2")
            allowMetadataWithoutPassword = false
        }
        installRoutes()

        val response = client.get("/api/shares/$share")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["needs_password"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(JsonNull, body["metadata"])
        assertEquals(JsonNull, body["content"])
        assertFalse(response.bodyAsText().contains("Die Rechnung"))
    }

    @Test
    fun `a link that ran out is gone, one that never was is not found`() = testApplication {
        val share = setUp { validUntil = Clock.System.now() - 1.days }
        installRoutes()

        // 410, not 404: the link was real, and that is what tells a reader to ask for a new one.
        assertEquals(HttpStatusCode.Gone, client.get("/api/shares/$share").status)
        assertEquals(HttpStatusCode.Gone, client.post("/api/shares/$share/open") {
            contentType(ContentType.Application.Json)
            setBody("""{"password": "hunter2"}""")
        }.status)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/shares/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/shares/nonsense").status)
    }

    /** A mail with a label on it, and a share of it that [share] shapes. */
    private suspend fun setUp(share: Share.() -> Unit): Uuid {
        database.init()
        database.query { Shares.deleteAll() }
        return database.query {
            owner = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            val account = ImapAccount.new {
                user = owner
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                user = owner
                address = "sender@example.com"
            }
            mail = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Die Rechnung"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                htmlContent = "<p>Hallo</p>"
                textContent = "Hallo"
            }
            val label = Label.new {
                name = "Rechnungen"
                color = "#eeeeff"
                owner = this@ShareViewTest.owner
                createdByAgent = false
            }
            EmailLabel.new {
                email = mail
                this.label = label
                labeledByAgent = false
            }

            Share.new {
                email = mail
                sharedAt = Clock.System.now()
                includeLabels = false
                share()
            }.id.value
        }
    }

    /** No authentication plugin at all: holding the link is the whole authorization. */
    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/shares/{shareId}") {
                    getShare()

                    route("/open") {
                        openShare()
                    }
                }
            }
        }
    }
}

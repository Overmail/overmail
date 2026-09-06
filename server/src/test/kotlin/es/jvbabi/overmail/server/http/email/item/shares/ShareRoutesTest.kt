package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.data.share.SharePassword
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import es.jvbabi.overmail.server.http.email.item.shares.item.deleteShare
import es.jvbabi.overmail.server.http.email.item.shares.item.updateShare
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll

/** The links a reader hands out for one mail: making them, changing them, taking them back. */
class ShareRoutesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:shares;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var stranger: User

    @Test
    fun `a share is made, listed, changed and taken back`() = testApplication {
        val mail = setUp()
        installRoutes()
        val until = (Clock.System.now() + 7.days).epochSeconds

        val created = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "share_name": "  Projektgruppe  ",
                  "include_labels": true,
                  "valid_until": $until,
                  "password": "hunter2",
                  "allow_metadata_without_password": true
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, created.status)
        val share = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val shareId = share["id"]!!.jsonPrimitive.content
        assertEquals("Projektgruppe", share["share_name"]!!.jsonPrimitive.content)
        assertEquals(until, share["valid_until"]!!.jsonPrimitive.content.toLong())
        assertTrue(share["include_labels"]!!.jsonPrimitive.content.toBoolean())
        // The password itself never leaves, only that there is one.
        assertTrue(share["has_password"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(created.bodyAsText().contains("hunter2"))

        // Stored hashed, so the row a leak would hand out is not the password.
        val stored = database.query { Shares.selectAll().single()[Shares.passwordHash] }
        assertNotNull(stored)
        assertTrue(SharePassword.verify("hunter2", stored))

        val listed = client.get("/api/emails/$mail/shares")
        assertEquals(HttpStatusCode.OK, listed.status)
        val shares = Json.parseToJsonElement(listed.bodyAsText()).jsonObject["shares"]!!.jsonArray
        assertEquals(1, shares.size)
        assertEquals(shareId, shares.single().jsonObject["id"]!!.jsonPrimitive.content)

        // An edit without a password leaves the one that is there -- the screen never had it.
        val renamed = client.put("/api/emails/$mail/shares/$shareId") {
            contentType(ContentType.Application.Json)
            setBody("""{"share_name": "Nur Anna", "include_labels": false}""")
        }
        assertEquals(HttpStatusCode.OK, renamed.status)
        val edited = Json.parseToJsonElement(renamed.bodyAsText()).jsonObject
        assertEquals("Nur Anna", edited["share_name"]!!.jsonPrimitive.content)
        assertFalse(edited["include_labels"]!!.jsonPrimitive.content.toBoolean())
        // Cleared, because the request said nothing about a date it should keep.
        assertTrue(edited["valid_until"] is kotlinx.serialization.json.JsonNull)
        assertTrue(edited["has_password"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(shareId, edited["id"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/emails/$mail/shares/$shareId").status)
        assertEquals(0, database.query { Shares.selectAll().count() })
        // And a second delete is a miss, not a second success.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/emails/$mail/shares/$shareId").status)
    }

    @Test
    fun `a password is set, replaced and taken off again`() = testApplication {
        val mail = setUp()
        installRoutes()

        val created = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false}""")
        }
        val shareId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertNull(database.query { Shares.selectAll().single()[Shares.passwordHash] })

        val locked = client.put("/api/emails/$mail/shares/$shareId") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "password": "erstes"}""")
        }
        assertEquals(HttpStatusCode.OK, locked.status)
        assertTrue(SharePassword.verify("erstes", database.query { Shares.selectAll().single()[Shares.passwordHash]!! }))

        client.put("/api/emails/$mail/shares/$shareId") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "password": "zweites"}""")
        }
        val replaced = database.query { Shares.selectAll().single()[Shares.passwordHash]!! }
        assertTrue(SharePassword.verify("zweites", replaced))
        assertFalse(SharePassword.verify("erstes", replaced))

        val opened = client.put("/api/emails/$mail/shares/$shareId") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "remove_password": true}""")
        }
        assertFalse(
            Json.parseToJsonElement(opened.bodyAsText())
                .jsonObject["has_password"]!!.jsonPrimitive.content.toBoolean()
        )
        assertNull(database.query { Shares.selectAll().single()[Shares.passwordHash] })
    }

    @Test
    fun `a date in the past and a password nobody could type are refused`() = testApplication {
        val mail = setUp()
        installRoutes()

        val expired = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "valid_until": ${(Clock.System.now() - 1.days).epochSeconds}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, expired.status)

        val short = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "password": "ab"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)

        // Blank is not a password, it is a link that asks for none.
        val blank = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false, "password": "   "}""")
        }
        assertEquals(HttpStatusCode.Created, blank.status)
        assertNull(database.query { Shares.selectAll().single()[Shares.passwordHash] })
    }

    @Test
    fun `the body the dialog sends is the body the route takes`() = testApplication {
        val mail = setUp()
        installRoutes()
        val until = (Clock.System.now() + 30.days).epochSeconds

        // Verbatim what `ShareRepository.create` builds, keys and all: the request is read
        // strictly, so a key this route does not know would be a 400 and not an ignored extra.
        val response = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"share_name":"test","include_labels":true,"valid_until":$until,""" +
                    """"password":null,"allow_metadata_without_password":true}"""
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `a share only answers under the mail it belongs to, and only to its owner`() = testApplication {
        val mail = setUp()
        installRoutes()

        val created = client.post("/api/emails/$mail/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"include_labels": false}""")
        }
        val shareId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val otherMail = database.query {
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

        // Somebody else's mail is a 403, before the share is even looked at.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/emails/$otherMail/shares").status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/emails/$otherMail/shares/$shareId").status)

        // A share id that is not this mail's -- or not an id at all -- is a miss.
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/emails/$mail/shares/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/emails/$mail/shares/nonsense").status)
        assertEquals(1, database.query { Shares.selectAll().count() })
    }

    /** A user with a mail of their own, plus somebody else to be refused as. */
    private suspend fun setUp(): Uuid {
        database.init()
        database.query { Shares.deleteAll() }
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
            Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Mail"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
            }.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/emails/{emailId}/shares") {
                    getShares()
                    newShare()

                    route("/{shareId}") {
                        updateShare()
                        deleteShare()
                    }
                }
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

package es.jvbabi.overmail.server.http.api

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.email.item.read.setEmailRead
import es.jvbabi.overmail.server.http.email.list.emailList
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a failing request looks like. One shape for all of them, so a client parses the answer it
 * gets rather than the answer it hoped for.
 */
class ApiErrorTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:api-errors;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var stranger: User

    @Test
    fun `an id that is not one is a miss, like an id nothing is behind`() = testApplication {
        setUp()
        installRoutes()

        val malformed = client.post("/api/emails/not-a-uuid/read")
        assertEquals(HttpStatusCode.NotFound, malformed.status)
        assertEquals("not_found", malformed.error()["code"]!!.jsonPrimitive.content)
        assertEquals("email", malformed.error()["details"]!!.jsonObject["resource"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.NotFound, client.post("/api/emails/${Uuid.random()}/read").status)
    }

    @Test
    fun `a mail of somebody else is refused, not hidden`() = testApplication {
        val theirs = setUp()
        installRoutes()

        val response = client.post("/api/emails/$theirs/read")
        assertEquals(HttpStatusCode.Forbidden, response.status)

        val error = response.error()
        assertEquals("forbidden", error["code"]!!.jsonPrimitive.content)
        assertEquals(403, error["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(theirs.toString(), error["details"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a parameter the server cannot work with names itself`() = testApplication {
        setUp()
        installRoutes()

        val response = client.get("/api/emails/list?limit=soon")
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val error = response.error()
        assertEquals("invalid_request", error["code"]!!.jsonPrimitive.content)
        assertEquals("limit", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
        assertEquals("soon", error["details"]!!.jsonObject["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a route nobody serves answers in the same shape`() = testApplication {
        setUp()
        installRoutes()

        val response = client.get("/api/nothing-here")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals("not_found", response.error()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no session is a 401, so the frontend can send the caller to sign in`() = testApplication {
        setUp()
        installRoutes()

        val response = client.get("/api/who-is-there")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("unauthenticated", response.error()["code"]!!.jsonPrimitive.content)
    }

    private suspend fun HttpResponse.error() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject

    /** Two users with a mailbox each. The mail that comes back is the stranger's. */
    private suspend fun setUp(): Uuid {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "reader-${Uuid.random()}"
                email = "reader-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "Reader"
            }
            stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "Stranger"
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
            }.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoutes() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<MailNotifier> { MailNotifier() }
            }
            routing {
                route("/api/emails/{emailId}/read") { setEmailRead(isRead = true) }
                route("/api/emails/list") { emailList() }
                // Deliberately outside authenticate { }: this is the helper answering on its own.
                get("/api/who-is-there") { call.respond(call.requireAuthenticatedUser().username) }
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

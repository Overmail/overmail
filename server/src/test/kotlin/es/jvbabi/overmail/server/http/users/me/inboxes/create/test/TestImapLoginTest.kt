package es.jvbabi.overmail.server.http.users.me.inboxes.create.test

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
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
import kotlin.test.assertFalse
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val ROUTE = "/api/users/me/inboxes/create/test/imap-login"

class TestImapLoginTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:imap-login-test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `a login without a username names the field that is wrong`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":993,"username":"","password":"x"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("username", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a session no credentials are tried`() = testApplication {
        signedIn = null
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":993,"username":"u","password":"p"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a mailbox that is gone is not a rejected password`() = runBlocking {
        // The step before this one said the host answers; that it no longer does is its own
        // outcome, and the dialog sends the user back rather than blaming the credentials.
        val port = ServerSocket(0).use { it.localPort }

        val result = probeImapLogin("127.0.0.1", port, "user", "secret")

        assertFalse(result.authenticated)
        assertEquals(ImapLoginTestOutcome.CONNECTION_FAILED.wire, result.outcome)
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
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route(ROUTE) { testImapLogin() }
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

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
import kotlin.uuid.Uuid
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val ROUTE = "/api/users/me/inboxes/create/test/imap-host"

class TestImapHostTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:imap-host-test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `a host that is only whitespace names the field that is wrong`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"   ","port":993}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("invalid_request", error["code"]!!.jsonPrimitive.content)
        assertEquals("host", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a port outside the range is not probed`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":70000}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("port", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a session nothing is probed`() = testApplication {
        signedIn = null
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":993}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `nothing listening on the port is a refused connection, not a failed request`() = runBlocking {
        // Bound only to learn a port that is free, then given up again.
        val port = ServerSocket(0).use { it.localPort }

        val result = probeImapHost("127.0.0.1", port)

        assertFalse(result.reachable)
        assertEquals(ImapHostTestOutcome.CONNECTION_FAILED.wire, result.outcome)
        assertEquals(emptyList(), result.capabilities)
    }

    @Test
    fun `a server that answers in plaintext fails the handshake`() = runBlocking {
        // What port 143 looks like: a real imap greeting, but no tls under it.
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { it.getOutputStream().write("* OK IMAP4rev1 ready\r\n".toByteArray()) }
            }
        }

        val result = server.use { probeImapHost("127.0.0.1", it.localPort) }

        assertFalse(result.reachable)
        assertEquals(ImapHostTestOutcome.TLS_FAILED.wire, result.outcome)
    }

    @Test
    fun `a server that accepts but never hand shakes is a failed handshake, not a bare timeout`() = runBlocking {
        // The other shape of a plaintext port: the connection comes up and then nothing happens.
        // Only the step the probe ran out of time in tells this apart from an unroutable host.
        val server = ServerSocket(0)
        thread(isDaemon = true) { runCatching { server.accept().use { Thread.sleep(30_000) } } }

        val result = server.use { probeImapHost("127.0.0.1", it.localPort) }

        assertFalse(result.reachable)
        assertEquals(ImapHostTestOutcome.TLS_FAILED.wire, result.outcome)
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
                route(ROUTE) { testImapHost() }
            }
        }
    }

    /** Signed in as whoever [signedIn] holds, and a bare 401 while it holds nobody. */
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

package es.jvbabi.overmail.server.http.users.me.inboxes.create.folders

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val ROUTE = "/api/users/me/inboxes/create/folders/stream"

class StreamInboxFoldersTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:inbox-folders;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `a mailbox that cannot be opened is reported inside the stream`() = testApplication {
        setUpUser()
        installRoute()
        // Bound only to learn a free port, then given up again.
        val port = ServerSocket(0).use { it.localPort }

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"127.0.0.1","port":$port,"username":"u","password":"p"}""")
        }

        // The status is 200: the response began before the mailbox was ever opened, so the
        // failure has nowhere to go but into the stream.
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.headers[HttpHeaders.ContentType]!!.startsWith(ContentType.Text.EventStream.toString()),
            response.headers[HttpHeaders.ContentType]!!,
        )

        val body = response.bodyAsText()
        val frames = body.split("\n\n").filter { it.isNotBlank() }
        assertEquals(1, frames.size, body)
        assertTrue(frames[0].startsWith("data: "), body)

        val event = Json.parseToJsonElement(frames[0].removePrefix("data: ")).jsonObject
        assertEquals("error", event["type"]!!.jsonPrimitive.content)
        assertEquals("mailbox_unavailable", event["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a request without a port never opens a stream`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":0,"username":"u","password":"p"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("port", error["details"]!!.jsonObject["parameter"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a session no mailbox is opened`() = testApplication {
        signedIn = null
        installRoute()

        val response = client.post(ROUTE) {
            contentType(ContentType.Application.Json)
            setBody("""{"host":"imap.example.com","port":993,"username":"u","password":"p"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
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
                route(ROUTE) { streamInboxFolders() }
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

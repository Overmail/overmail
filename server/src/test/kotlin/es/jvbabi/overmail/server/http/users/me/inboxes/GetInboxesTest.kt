package es.jvbabi.overmail.server.http.users.me.inboxes

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSync
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

private const val ROUTE = "/api/users/me/inboxes"

class GetInboxesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:get-inboxes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private var signedIn: User? = null

    @Test
    fun `lists this user's mailboxes with the folders each one syncs`() = testApplication {
        val user = setUpUser()
        installRoute()

        database.query {
            val account = ImapAccount.new {
                this.user = user
                host = "imap.example.com"
                port = 993
                username = "julius@example.com"
                password = "secret"
            }
            // Inserted out of order, to show the answer is sorted rather than lucky.
            listOf("Sent", "Archiv/Newsletter", "INBOX").forEach { name ->
                ImapAccountFolderSync.new {
                    imapAccount = account
                    folder = name
                    imapPush = name == "INBOX"
                    aiImport = ImapAccountFolderSync.AiImportSettings.AllMessages
                }
            }
        }

        val response = client.get(ROUTE)
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val inboxes = Json.parseToJsonElement(body).jsonObject["inboxes"]!!.jsonArray
        assertEquals(1, inboxes.size)

        val inbox = inboxes[0].jsonObject
        assertEquals("imap.example.com", inbox["host"]!!.jsonPrimitive.content)
        assertEquals(993, inbox["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("julius@example.com", inbox["username"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("Archiv/Newsletter", "INBOX", "Sent"),
            inbox["folders"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // Nothing on a screen needs it, so it must not leave the row.
        assertFalse(body.contains("secret"), body)
        assertFalse(body.contains("password"), body)
    }

    @Test
    fun `a mailbox belonging to somebody else is not listed`() = testApplication {
        setUpUser()
        installRoute()

        database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Someone"
                lastname = "Else"
            }
            ImapAccount.new {
                user = stranger
                host = "imap.stranger.example"
                port = 993
                username = "stranger@example.com"
                password = "secret"
            }
        }

        val body = client.get(ROUTE).bodyAsText()
        assertFalse(body.contains("imap.stranger.example"), body)
    }

    @Test
    fun `a user without a mailbox gets an empty list, not an error`() = testApplication {
        setUpUser()
        installRoute()

        val response = client.get(ROUTE)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            0,
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["inboxes"]!!.jsonArray.size,
        )
    }

    @Test
    fun `without a session there is nothing to list`() = testApplication {
        setUpUser()
        signedIn = null
        installRoute()

        assertEquals(HttpStatusCode.Unauthorized, client.get(ROUTE).status)
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
                route(ROUTE) { getInboxes() }
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

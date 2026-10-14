package es.jvbabi.overmail.server.http.email.item.meta

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/** One mail or several by their ids in the path, and nothing of anybody else's. */
class GetEmailMetaTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:get-email-meta;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `one id answers the mail itself`() = testApplication {
        val (own, account) = setUp()
        installRoute()

        val response = client.get("/api/emails/${own.first()}")

        assertEquals(HttpStatusCode.OK, response.status)
        val email = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(own.first().toString(), email.getValue("id").jsonPrimitive.content)
        assertEquals(account.toString(), email.getValue("imap_account_id").jsonPrimitive.content)
    }

    @Test
    fun `a list of ids answers an array in their order`() = testApplication {
        val (own, _) = setUp()
        installRoute()

        val response = client.get("/api/emails/${own.reversed().joinToString(",")}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(own.reversed().map { it.toString() }, response.ids())
    }

    @Test
    fun `a trailing comma makes an array of one`() = testApplication {
        val (own, _) = setUp()
        installRoute()

        assertEquals(listOf(own.first().toString()), client.get("/api/emails/${own.first()},").ids())
    }

    @Test
    fun `a list with a mail of somebody else is forbidden`() = testApplication {
        val (foreign, _) = setUp()
        val (own, _) = setUp()
        installRoute()

        // setUp signs the last one in, so the first one's mails are somebody else's now.
        val response = client.get("/api/emails/${own.first()},${foreign.first()}")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(foreign.first().toString() in response.bodyAsText())
    }

    @Test
    fun `a list with an unknown or malformed id is not found`() = testApplication {
        val (own, _) = setUp()
        installRoute()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/emails/${own.first()},${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/emails/${own.first()},nope").status)
    }

    @Test
    fun `more ids than a url may name are refused`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/${List(101) { Uuid.random() }.joinToString(",")}").status)
    }

    private suspend fun HttpResponse.ids() =
        Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    /** A user with two mails, signed in; the mails and their inbox. */
    private suspend fun setUp(): Pair<List<Uuid>, Uuid> {
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
                address = "sender-${Uuid.random()}@example.com"
            }

            val mails = List(2) {
                Email.new {
                    imapAccount = account
                    this.sender = sender
                    senderName = "The Sender"
                    subject = "Mail"
                    sent = Clock.System.now()
                    rawContent = ByteArray(0)
                }.id.value
            }
            mails to account.id.value
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            install(Koin) { modules(module { single<OvermailDatabase> { database } }) }
            routing {
                route("/api/emails/{emailId}") { getEmailMeta() }
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

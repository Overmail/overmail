package es.jvbabi.overmail.server.http.email.meta

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The whole metadata of a list of mails: what a client database is filled from. */
class EmailsMetaTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:emails-meta;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `the mails come back whole`() = testApplication {
        val (own, account) = setUp()
        installRoute()

        val emails = ask(own).emails()

        assertEquals(own.map { it.toString() }.toSet(), emails.map { it.getValue("id").jsonPrimitive.content }.toSet())
        emails.forEach { email ->
            assertEquals(account.toString(), email.getValue("imap_account_id").jsonPrimitive.content)
            assertEquals("unarchive", email.getValue("archive_state").jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown and foreign ids are left out`() = testApplication {
        val (own, _) = setUp()
        val (foreign, _) = setUp()
        installRoute()

        // setUp signs the last one in, so the first one's mails are somebody else's now.
        val emails = ask(own + foreign + Uuid.random()).emails()

        assertEquals(foreign.map { it.toString() }.toSet(), emails.map { it.getValue("id").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `more ids than a request may carry are refused`() = testApplication {
        setUp()
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, ask(List(501) { Uuid.random() }).status)
    }

    private suspend fun ApplicationTestBuilder.ask(ids: List<Uuid>): HttpResponse =
        client.request("/api/emails/meta") {
            method = HttpMethod.Query
            contentType(ContentType.Application.Json)
            setBody("""{"ids": [${ids.joinToString(",") { "\"$it\"" }}]}""")
        }

    private suspend fun HttpResponse.emails() =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("emails").jsonArray.map { it.jsonObject }

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
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/emails/meta") { emailsMeta() }
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

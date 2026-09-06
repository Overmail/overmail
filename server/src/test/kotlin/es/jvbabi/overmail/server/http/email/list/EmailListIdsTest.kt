package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
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
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** A whole stretch of the mailbox at once: what picking one in the table asks for. */
class EmailListIdsTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-list-ids;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `a range answers with every mail in it, newest first`() = testApplication {
        val mails = setUp(count = 5)
        installRoute()

        // The fixture puts one mail per day, newest first, so this is today and the two days
        // under it -- the stretch a header would stand over.
        val answer = client
            .get("/api/emails/list/ids?from=${startOfDay(2)}&to=${startOfDay(-1)}")
            .body()

        assertEquals(3, answer["total"]!!.jsonPrimitive.long)
        assertEquals(mails.take(3).map { it.toString() }, answer.ids())
    }

    @Test
    fun `the upper end is the boundary of the next stretch, not part of this one`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()

        // Midnight of today: what the stretch below it ends at, so today's mail is not in it.
        val answer = client.get("/api/emails/list/ids?to=${startOfDay(0)}").body()

        assertEquals(mails.drop(1).map { it.toString() }, answer.ids())
    }

    @Test
    fun `without bounds it is the whole scope`() = testApplication {
        val mails = setUp(count = 4)
        installRoute()

        assertEquals(mails.map { it.toString() }, client.get("/api/emails/list/ids").ids())
    }

    @Test
    fun `a stretch holds what its scope holds`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        // Picking a stretch of the mailbox must not pick the mails that were put away out of it,
        // and spam is in neither scope.
        assertEquals(listOf(mails[2].toString()), client.get("/api/emails/list/ids").ids())
        assertEquals(
            listOf(mails[1], mails[2]).map { it.toString() },
            client.get("/api/emails/list/ids?scope=all").ids(),
        )
    }

    @Test
    fun `a bound that is not a time is refused`() = testApplication {
        setUp(count = 1)
        installRoute()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/ids?from=gestern").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/ids?to=heute").status,
        )
    }

    /** Midnight [daysBack] days ago in the server's zone, in epoch seconds. */
    private fun startOfDay(daysBack: Int): Long {
        val zone = TimeZone.currentSystemDefault()
        return (Clock.System.now() - daysBack.days).toLocalDateTime(zone).date
            .atStartOfDayIn(zone).epochSeconds
    }

    private suspend fun io.ktor.client.statement.HttpResponse.body() =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.ids() = body().ids()

    private fun kotlinx.serialization.json.JsonObject.ids() =
        getValue("ids").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun archive(emailId: Uuid, action: EmailArchiveAction) {
        database.query {
            EmailArchive.new {
                email = Email.findById(emailId)!!
                this.action = action
                createdAt = Clock.System.now()
                createdByAgent = false
            }
        }
    }

    /** [count] mails, one per day and newest first in the returned list. */
    private suspend fun setUp(count: Int): List<Uuid> {
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
                address = "sender@example.com"
            }
            val now = Clock.System.now()

            (0 until count).map { index ->
                Email.new {
                    imapAccount = account
                    this.sender = sender
                    senderName = "The Sender"
                    subject = "Mail $index"
                    sent = now - index.days
                    rawContent = ByteArray(0)
                }.id.value
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies { provide<OvermailDatabase> { database } }
            routing {
                route("/api/emails/list/ids") { emailListIds() }
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

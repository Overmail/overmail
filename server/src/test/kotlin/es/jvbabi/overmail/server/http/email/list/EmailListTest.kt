package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.truncatedToSecond
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** The mailbox by position: what a windowed table asks for a slice of. */
class EmailListTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-list;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `answers a page, newest first, with the length of the whole list`() = testApplication {
        val mails = setUp(count = 5)
        installRoute()

        val page = client.get("/api/emails/list?limit=2").page()

        assertEquals(5, page["total"]!!.jsonPrimitive.long)
        assertEquals(mails.take(2).map { it.toString() }, page.ids())
    }

    @Test
    fun `the cursor of a page is where the next one carries on`() = testApplication {
        val mails = setUp(count = 5)
        installRoute()

        val first = client.get("/api/emails/list?limit=2").page()
        val cursor = first["next"]!!.jsonObject
        val second = client
            .get("/api/emails/list?limit=2&before=${cursor["before"]!!.jsonPrimitive.long}" +
                    "&before_id=${cursor["before_id"]!!.jsonPrimitive.content}")
            .page()

        // Where the last page ended, not one mail earlier or later.
        assertEquals(mails.subList(2, 4).map { it.toString() }, second.ids())
    }

    @Test
    fun `the last page says there is nothing after it`() = testApplication {
        setUp(count = 2)
        installRoute()

        val page = client.get("/api/emails/list?limit=10").page()

        assertEquals(JsonNull, page["next"])
    }

    @Test
    fun `a day boundary as the cursor lands between two days`() = testApplication {
        val mails = setUp(count = 4)
        installRoute()

        // The fixture puts one mail per day, newest first, so midnight of the newest day is the
        // boundary the second mail sits below -- which is how a table jumps to a date.
        val startOfToday = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
            .atStartOfDayIn(TimeZone.currentSystemDefault())

        val page = client.get("/api/emails/list?before=${startOfToday.epochSeconds}").page()

        assertEquals(mails.drop(1).map { it.toString() }, page.ids())
    }

    @Test
    fun `mails sharing a second are neither repeated nor skipped`() = testApplication {
        setUp(count = 0)
        installRoute()
        // Three mails in the same second: without the id as a tiebreaker their order is the
        // database's mood, and a page boundary inside them loses one or hands it out twice.
        // Truncated, as every writer stores it -- see Emails.sent, which is a dedup key at
        // second precision, and which is what lets the cursor be a second.
        val sameSecond = Clock.System.now().truncatedToSecond()
        repeat(3) { addMail(sameSecond) }

        val first = client.get("/api/emails/list?limit=2").page()
        val cursor = first["next"]!!.jsonObject
        val second = client
            .get("/api/emails/list?limit=2&before=${cursor["before"]!!.jsonPrimitive.long}" +
                    "&before_id=${cursor["before_id"]!!.jsonPrimitive.content}")
            .page()

        val seen = first.ids() + second.ids()
        assertEquals(3, seen.size)
        assertEquals(3, seen.toSet().size)
    }

    @Test
    fun `the mailbox as it stands holds neither spam nor archived mails`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list").page()

        // Putting a mail away has to mean something in the listing it was put away from.
        assertEquals(1, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[2].toString()), page.ids())
    }

    @Test
    fun `every mail is in the other scope, spam still is not`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list?scope=all").page()

        assertEquals(2, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[1], mails[2]).map { it.toString() }, page.ids())
    }

    @Test
    fun `a scope nobody offers is refused`() = testApplication {
        setUp(count = 1)
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?scope=spam").status)
    }

    @Test
    fun `a mail taken back out of the archive is in the listing again`() = testApplication {
        val mails = setUp(count = 1)
        installRoute()
        archive(mails[0], EmailArchiveAction.Archive)
        archive(mails[0], EmailArchiveAction.Unarchive)

        // Only the latest event of the log counts, see emailIsNotArchived.
        assertEquals(listOf(mails[0].toString()), client.get("/api/emails/list").ids())
    }

    @Test
    fun `a mail of another user is not in the list`() = testApplication {
        setUp(count = 1)
        installRoute()
        database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
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
            }
        }

        assertEquals(1, client.get("/api/emails/list").page()["total"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a nonsense request is answered with what there is`() = testApplication {
        setUp(count = 2)
        installRoute()

        // Before the epoch: an empty page rather than an error, the length still reported.
        val beyond = client.get("/api/emails/list?before=0&limit=5000").page()
        assertEquals(2, beyond["total"]!!.jsonPrimitive.long)
        assertEquals(0, beyond.ids().size)

        // The limit is clamped into what one request may ask for.
        assertEquals(1, client.get("/api/emails/list?limit=0").ids().size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?before=heute").status)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.page() =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.ids() = page().ids()

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

    private suspend fun addMail(sentAt: kotlin.time.Instant): Uuid = database.query {
        Email.new {
            imapAccount = ImapAccount.all().first { it.user.id == signedIn.id }
            sender = EmailUser.all().first()
            senderName = "The Sender"
            subject = "Mail at $sentAt"
            sent = sentAt
            rawContent = ByteArray(0)
        }.id.value
    }

    /** [count] mails, newest first in the returned list. */
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
                route("/api/emails/list") { emailList() }
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

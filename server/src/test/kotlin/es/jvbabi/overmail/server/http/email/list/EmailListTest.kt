package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailRecipient
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.Label
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
    fun `an offset carries on where the page before it ended`() = testApplication {
        val mails = setUp(count = 5)
        installRoute()

        val second = client.get("/api/emails/list?limit=2&offset=2").page()

        // Where the last page ended, not one mail earlier or later.
        assertEquals(mails.subList(2, 4).map { it.toString() }, second.ids())
    }

    @Test
    fun `an offset past the end is an empty page, and still says how long the group is`() =
        testApplication {
            setUp(count = 2)
            installRoute()

            val page = client.get("/api/emails/list?offset=50").page()

            assertEquals(2, page["total"]!!.jsonPrimitive.long)
            assertEquals(0, page.ids().size)
        }

    @Test
    fun `a group is the mails under it`() = testApplication {
        val mails = setUp(count = 4)
        installRoute()

        // The fixture puts one mail per day, newest first, so the day below today holds exactly
        // the second one -- which is how a table asks for the rows of a stretch.
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val yesterday = kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - 1)

        val page = client.get("/api/emails/list?by=day&group=$yesterday").page()

        assertEquals(1, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[1].toString()), page.ids())
    }

    @Test
    fun `a group of a deeper level is the mails under that path`() = testApplication {
        val mails = setUp(count = 2)
        installRoute()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val other = addSender("other@example.com", mails[0])

        // That day and that correspondent: the one mail of theirs.
        assertEquals(
            listOf(mails[0].toString()),
            client.get("/api/emails/list?by=day,sender&group=$today,$other").ids(),
        )
        // One key alone is the level above it -- the whole day, whoever wrote.
        assertEquals(1, client.get("/api/emails/list?by=day,sender&group=$today").ids().size)
    }

    @Test
    fun `mails sharing a second are neither repeated nor skipped`() = testApplication {
        setUp(count = 0)
        installRoute()
        // Three mails in the same second: without the id as a tiebreaker their order is the
        // database's mood, and a page boundary inside them loses one or hands it out twice.
        val sameSecond = Clock.System.now().truncatedToSecond()
        repeat(3) { addMail(sameSecond) }

        val first = client.get("/api/emails/list?limit=2").ids()
        val second = client.get("/api/emails/list?limit=2&offset=2").ids()

        val seen = first + second
        assertEquals(3, seen.size)
        assertEquals(3, seen.toSet().size)
    }

    @Test
    fun `the sort orders the mails inside the group`() = testApplication {
        setUp(count = 0)
        installRoute()
        val now = Clock.System.now().truncatedToSecond()
        val b = addMail(now, subject = "Bravo")
        val a = addMail(now - 1.days, subject = "Alpha")

        assertEquals(listOf(b, a).map { it.toString() }, client.get("/api/emails/list").ids())
        assertEquals(
            listOf(a, b).map { it.toString() },
            client.get("/api/emails/list?sort=date:r").ids(),
        )
        assertEquals(
            listOf(a, b).map { it.toString() },
            client.get("/api/emails/list?sort=subject").ids(),
        )
        assertEquals(
            listOf(b, a).map { it.toString() },
            client.get("/api/emails/list?sort=subject:r").ids(),
        )
    }

    @Test
    fun `the mailbox as it stands holds neither spam nor archived mails`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list?archived_state=Unarchive").page()

        // Putting a mail away has to mean something in the listing it was put away from.
        assertEquals(1, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[2].toString()), page.ids())
    }

    @Test
    fun `the archived ones come back when the filter asks for them`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list?archived_state=Unarchive,Archive").page()

        assertEquals(2, page["total"]!!.jsonPrimitive.long)
        assertEquals(listOf(mails[1], mails[2]).map { it.toString() }, page.ids())
    }

    @Test
    fun `spam is a state like any other, and only what is asked for is in`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        archive(mails[0], EmailArchiveAction.Spam)
        archive(mails[1], EmailArchiveAction.Archive)

        val page = client.get("/api/emails/list?archived_state=Spam").page()

        assertEquals(listOf(mails[0].toString()), page.ids())
    }

    @Test
    fun `a filter that names no state at all lets nothing through`() = testApplication {
        setUp(count = 3)
        installRoute()

        // Not the same as leaving the parameter out, which is every state there is.
        val page = client.get("/api/emails/list?archived_state=").page()

        assertEquals(0, page["total"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a state nobody offers is refused`() = testApplication {
        setUp(count = 1)
        installRoute()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?archived_state=spam").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?has_labels=not-an-id").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?read_state=maybe").status)
    }

    @Test
    fun `the read state cuts the listing both ways`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        markRead(mails[1])

        assertEquals(listOf(mails[1].toString()), client.get("/api/emails/list?read_state=true").ids())
        assertEquals(
            listOf(mails[0], mails[2]).map { it.toString() },
            client.get("/api/emails/list?read_state=false").ids(),
        )
    }

    @Test
    fun `a label filter holds the mails carrying any of them`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        val work = addLabel("Work", mails[0])
        val bills = addLabel("Bills", mails[2])

        assertEquals(listOf(mails[0].toString()), client.get("/api/emails/list?has_labels=$work").ids())
        // Any of them, not all: two labels are two ways in, not a mail that has to carry both.
        assertEquals(
            listOf(mails[0], mails[2]).map { it.toString() },
            client.get("/api/emails/list?has_labels=$work,$bills").ids(),
        )
    }

    @Test
    fun `who wrote and who was written to are two filters`() = testApplication {
        val mails = setUp(count = 2)
        installRoute()
        val other = addSender("other@example.com", mails[0])
        val recipient = addRecipient("team@example.com", mails[1])

        assertEquals(listOf(mails[0].toString()), client.get("/api/emails/list?sent_by=$other").ids())
        assertEquals(listOf(mails[1].toString()), client.get("/api/emails/list?sent_to=$recipient").ids())
    }

    @Test
    fun `an account filter holds the mails that came through it`() = testApplication {
        val mails = setUp(count = 2)
        installRoute()
        val second = addAccount("imap.other.example.com", mails[0])

        assertEquals(listOf(mails[0].toString()), client.get("/api/emails/list?imap_account_ids=$second").ids())
    }

    @Test
    fun `filters are read together, not as alternatives`() = testApplication {
        val mails = setUp(count = 3)
        installRoute()
        val work = addLabel("Work", mails[0], mails[1])
        markRead(mails[1])

        assertEquals(
            listOf(mails[1].toString()),
            client.get("/api/emails/list?has_labels=$work&read_state=true").ids(),
        )
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

        // The limit is clamped into what one request may ask for.
        assertEquals(1, client.get("/api/emails/list?limit=0").ids().size)
        assertEquals(2, client.get("/api/emails/list?limit=5000").ids().size)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?sort=oldest").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?by=day&group=heute").status)
        // More keys than levels: a client that has the groups has the keys.
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/emails/list?by=day&group=a,b").status)
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

    private suspend fun markRead(emailId: Uuid) {
        database.query { Email.findById(emailId)!!.isRead = true }
    }

    /** A label of this user, put on [mails]. */
    private suspend fun addLabel(name: String, vararg mails: Uuid): Uuid = database.query {
        val label = Label.new {
            owner = signedIn
            this.name = name
            color = "#000000"
            createdByAgent = false
        }
        for (mailId in mails) {
            EmailLabel.new {
                email = Email.findById(mailId)!!
                this.label = label
                labeledByAgent = false
            }
        }
        label.id.value
    }

    /** A second correspondent, as the sender of [mail]. */
    private suspend fun addSender(address: String, mail: Uuid): Uuid = database.query {
        val sender = EmailUser.new {
            user = signedIn
            this.address = address
        }
        Email.findById(mail)!!.sender = sender
        sender.id.value
    }

    /** A second correspondent, as a recipient of [mail]. */
    private suspend fun addRecipient(address: String, mail: Uuid): Uuid = database.query {
        val recipient = EmailUser.new {
            user = signedIn
            this.address = address
        }
        EmailRecipient.new {
            email = Email.findById(mail)!!
            emailUser = recipient
            type = EmailRecipientType.RECIPIENT
        }
        recipient.id.value
    }

    /** A second mailbox of this user, as the one [mail] came through. */
    private suspend fun addAccount(host: String, mail: Uuid): Uuid = database.query {
        val account = ImapAccount.new {
            user = signedIn
            this.host = host
            port = 993
            username = "owner"
            password = "secret"
        }
        Email.findById(mail)!!.imapAccount = account
        account.id.value
    }

    private suspend fun addMail(sentAt: kotlin.time.Instant, subject: String? = null): Uuid = database.query {
        Email.new {
            imapAccount = ImapAccount.all().first { it.user.id == signedIn.id }
            sender = EmailUser.all().first()
            senderName = "The Sender"
            this.subject = subject ?: "Mail at $sentAt"
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

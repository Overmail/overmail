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
import kotlinx.datetime.LocalDate
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The shape of the listing: how long each stretch of it is, before a single mail is loaded. */
class EmailListGroupsTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-groups;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User
    private lateinit var account: ImapAccount
    private lateinit var sender: EmailUser

    private val zone = TimeZone.currentSystemDefault()
    private val today = Clock.System.now().toLocalDateTime(zone).date

    @Test
    fun `the smart date cuts today, yesterday and the months before`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(0))
        addMail(daysAgo(0))
        addMail(daysAgo(1))
        val old = daysAgo(400)
        addMail(old)

        val groups = client.get("/api/emails/list/groups?by=date_smart").groups()
            .associate { it.keys().single() to it["count"]!!.jsonPrimitive.long }

        assertEquals(2L, groups["1"])
        assertEquals(1L, groups["2"])
        // Over a year back, so it is the month it was sent in, whatever the near stretches are
        // doing today.
        val sent = old.toLocalDateTime(zone).date
        assertEquals(1L, groups["${sent.year * 100 + sent.month.ordinal + 1}"])
    }

    @Test
    fun `a year and a month are one number each`() = testApplication {
        setUp()
        installRoute()
        val sent = daysAgo(400)
        addMail(sent)
        val date = sent.toLocalDateTime(zone).date

        assertEquals(
            listOf(date.year.toString()),
            client.get("/api/emails/list/groups?by=year").groups().map { it.keys().single() },
        )
        assertEquals(
            listOf("${date.year * 100 + date.month.ordinal + 1}"),
            client.get("/api/emails/list/groups?by=month").groups().map { it.keys().single() },
        )
    }

    @Test
    fun `a day nothing arrived on is not a group`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(1))

        val groups = client.get("/api/emails/list/groups?by=day").groups()

        // Nothing today, so today is not in the answer -- a header never stands over nothing.
        assertEquals(listOf(day(1)), groups.map { it.keys().single() })
    }

    @Test
    fun `without grouping the whole mailbox is one group`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(0))
        addMail(daysAgo(3))

        val groups = client.get("/api/emails/list/groups").groups()

        assertEquals(1, groups.size)
        assertEquals(emptyList(), groups.single().keys())
        assertEquals(2, groups.single()["count"]!!.jsonPrimitive.long)
    }

    @Test
    fun `two levels are one row per combination that holds mail`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(0))
        val fromOther = addMail(daysAgo(0))
        addMail(daysAgo(1))
        val other = otherSender(fromOther)

        val groups = client.get("/api/emails/list/groups?by=day,sender").groups()

        // Today holds two senders, yesterday one -- and the keys come outermost first.
        assertEquals(
            mapOf(
                listOf(day(0), sender.id.value.toString()) to 1L,
                listOf(day(0), other.toString()) to 1L,
                listOf(day(1), sender.id.value.toString()) to 1L,
            ),
            groups.associate { it.keys() to it["count"]!!.jsonPrimitive.long },
        )
    }

    @Test
    fun `the read state is a grouping of its own`() = testApplication {
        setUp()
        installRoute()
        val read = addMail(daysAgo(0))
        addMail(daysAgo(0))
        markRead(read)

        val groups = client.get("/api/emails/list/groups?by=read").groups()

        assertEquals(
            mapOf("true" to 1L, "false" to 1L),
            groups.associate { it.keys().single() to it["count"]!!.jsonPrimitive.long },
        )
    }

    @Test
    fun `the archive state is a grouping of its own, event log and all`() = testApplication {
        setUp()
        installRoute()
        val archived = addMail(daysAgo(0))
        val spam = addMail(daysAgo(0))
        addMail(daysAgo(0))
        archive(archived, EmailArchiveAction.Archive)
        archive(spam, EmailArchiveAction.Spam)

        val groups = client.get("/api/emails/list/groups?by=archived&archived_state=Unarchive,Archive,Spam").groups()

        assertEquals(
            mapOf("Archive" to 1L, "Spam" to 1L, "Unarchive" to 1L),
            groups.associate { it.keys().single() to it["count"]!!.jsonPrimitive.long },
        )
    }

    @Test
    fun `the groups hold the same mails the listing does`() = testApplication {
        setUp()
        installRoute()
        val spam = addMail(daysAgo(0))
        val archived = addMail(daysAgo(0))
        addMail(daysAgo(0))
        addMail(daysAgo(2))
        archive(spam, EmailArchiveAction.Spam)
        archive(archived, EmailArchiveAction.Archive)

        val byDate = client.get("/api/emails/list/groups?by=date_smart&archived_state=Unarchive").groups()
            .sumOf { it["count"]!!.jsonPrimitive.long }
        val ungrouped = client.get("/api/emails/list/groups?archived_state=Unarchive").groups()
            .single()["count"]!!.jsonPrimitive.long

        // Spam and the archived mail are out of both, and every mail left is in exactly one
        // group -- which is what lets a layout be built from them.
        assertEquals(2, byDate)
        assertEquals(2, ungrouped)
    }

    @Test
    fun `the groups grow with the archived mails when the filter asks for them`() = testApplication {
        setUp()
        installRoute()
        val archived = addMail(daysAgo(0))
        addMail(daysAgo(0))
        archive(archived, EmailArchiveAction.Archive)

        val counted = client.get("/api/emails/list/groups?by=date_smart&archived_state=Unarchive,Archive").groups()
            .sumOf { it["count"]!!.jsonPrimitive.long }

        // The same filter the listing uses, or a header would count mails the rows do not show.
        assertEquals(2, counted)
    }

    @Test
    fun `an unknown grouping or filter is refused`() = testApplication {
        setUp()
        installRoute()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/groups?by=labels").status,
        )
        // The same level twice would be one header under another saying the same thing.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/groups?by=day,day").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/groups?by=day&archived_state=spam").status,
        )
    }

    private fun day(daysBack: Int) =
        LocalDate.fromEpochDays(today.toEpochDays() - daysBack).toString()

    /** Noon of a day [days] back, so no time zone offset can push it into another one. */
    private fun daysAgo(days: Int): Instant =
        LocalDate.fromEpochDays(today.toEpochDays() - days).atStartOfDayIn(zone) + 12.hours

    private suspend fun io.ktor.client.statement.HttpResponse.groups() =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("groups").jsonArray.map { it.jsonObject }

    /** The keys of one group row, outermost first. */
    private fun kotlinx.serialization.json.JsonObject.keys() =
        getValue("keys").jsonArray.map { it.jsonPrimitive.content }

    private suspend fun markRead(emailId: Uuid) {
        database.query { Email.findById(emailId)!!.isRead = true }
    }

    /** A second correspondent, as the sender of [mail]. */
    private suspend fun otherSender(mail: Uuid): Uuid = database.query {
        val other = EmailUser.new {
            user = signedIn
            address = "other@example.com"
        }
        Email.findById(mail)!!.sender = other
        other.id.value
    }

    private suspend fun addMail(sentAt: Instant): Uuid = database.query {
        Email.new {
            imapAccount = account
            this.sender = this@EmailListGroupsTest.sender
            senderName = "The Sender"
            subject = "Mail at $sentAt"
            sent = sentAt
            rawContent = ByteArray(0)
        }.id.value
    }

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

    private suspend fun setUp() {
        database.init()
        database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            account = ImapAccount.new {
                user = signedIn
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            sender = EmailUser.new {
                user = signedIn
                address = "sender@example.com"
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
                route("/api/emails/list/groups") { emailListGroups() }
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

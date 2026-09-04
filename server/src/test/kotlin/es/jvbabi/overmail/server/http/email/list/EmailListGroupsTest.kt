package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
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
import kotlinx.serialization.json.JsonNull
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
    fun `by date is one stretch per day, newest first`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(0))
        addMail(daysAgo(0))
        addMail(daysAgo(1))
        addMail(daysAgo(40))

        val groups = client.get("/api/emails/list/groups?by=date").groups()

        assertEquals(
            listOf(day(0) to 2L, day(1) to 1L, day(40) to 1L),
            groups.map { it["key"]!!.jsonPrimitive.content to it["count"]!!.jsonPrimitive.long },
        )
    }

    @Test
    fun `a day nothing arrived on is not a stretch`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(1))

        val groups = client.get("/api/emails/list/groups?by=date").groups()

        // Nothing today, so today is not in the answer -- a header never stands over nothing.
        assertEquals(listOf(day(1)), groups.map { it["key"]!!.jsonPrimitive.content })
    }

    @Test
    fun `without grouping the whole mailbox is one stretch`() = testApplication {
        setUp()
        installRoute()
        addMail(daysAgo(0))
        addMail(daysAgo(3))

        val groups = client.get("/api/emails/list/groups").groups()

        assertEquals(1, groups.size)
        assertEquals(JsonNull, groups.single()["key"])
        assertEquals(2, groups.single()["count"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the stretches hold the same mails the listing does`() = testApplication {
        setUp()
        installRoute()
        val spam = addMail(daysAgo(0))
        addMail(daysAgo(0))
        addMail(daysAgo(2))
        archive(spam, EmailArchiveAction.Spam)

        val byDate = client.get("/api/emails/list/groups?by=date").groups()
            .sumOf { it["count"]!!.jsonPrimitive.long }
        val ungrouped = client.get("/api/emails/list/groups?by=none").groups()
            .single()["count"]!!.jsonPrimitive.long

        // Spam is out of both, and every mail is in exactly one stretch -- which is what lets a
        // layout be built from them.
        assertEquals(2, byDate)
        assertEquals(2, ungrouped)
    }

    @Test
    fun `an unknown grouping is refused`() = testApplication {
        setUp()
        installRoute()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/emails/list/groups?by=sender").status,
        )
    }

    private fun day(daysBack: Int) =
        LocalDate.fromEpochDays(today.toEpochDays() - daysBack).toString()

    /** Noon of a day [days] back, so no time zone offset can push it into another one. */
    private fun daysAgo(days: Int): Instant =
        LocalDate.fromEpochDays(today.toEpochDays() - days).atStartOfDayIn(zone) + 12.hours

    private suspend fun io.ktor.client.statement.HttpResponse.groups() =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("groups").jsonArray.map { it.jsonObject }

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

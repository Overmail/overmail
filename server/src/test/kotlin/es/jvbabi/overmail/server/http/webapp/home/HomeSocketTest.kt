package es.jvbabi.overmail.server.http.webapp.home

import es.jvbabi.overmail.server.data.notifier.MailboxNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The home screen's live number: how many mails are in the mailbox right now. */
class HomeSocketTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:home-socket;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailboxNotifier = MailboxNotifier()

    private lateinit var signedIn: User

    @Test
    fun `counts the mails that are in the mailbox, archived ones excluded`() = testApplication {
        val mails = setUp()
        installRoute()
        // Of the three mails, one is archived and one was archived and taken back.
        archive(mails[1], EmailArchiveAction.Archive)
        archive(mails[2], EmailArchiveAction.Archive)
        archive(mails[2], EmailArchiveAction.Unarchive)

        val socket = openSocket()
        assertEquals(2, socket.nextCount())
        socket.close()
    }

    @Test
    fun `a mailbox that moves is reported again`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        assertEquals(3, socket.nextCount())

        archive(mails[0], EmailArchiveAction.Archive)
        mailboxNotifier.notifyMailboxChanged(signedIn.id.value)

        assertEquals(2, socket.nextCount())
        socket.close()
    }

    @Test
    fun `a change that leaves the number alone sends nothing`() = testApplication {
        val mails = setUp()
        installRoute()
        archive(mails[0], EmailArchiveAction.Archive)

        val socket = openSocket()
        assertEquals(2, socket.nextCount())

        // Archiving what is already archived: the count is the same, so the socket stays quiet.
        // Well past the debounce, or this would pass without proving anything.
        archive(mails[0], EmailArchiveAction.Archive)
        mailboxNotifier.notifyMailboxChanged(signedIn.id.value)

        assertNull(withTimeoutOrNull(2_000) { socket.nextCountOrNull() })
        socket.close()
    }

    private suspend fun ApplicationTestBuilder.openSocket(): WebSocketSession =
        createClient { install(ClientWebSockets) }.webSocketSession("/api/webapp/home/socket")

    /** The next message of [type], or a failure if the socket stays quiet. */
    private suspend fun WebSocketSession.next(type: String): JsonObject =
        withTimeout(5_000) { requireNotNull(nextOrNull(type)) { "no $type from the socket" } }

    /** Messages of other kinds are skipped: this socket sends the count and the graph. */
    private suspend fun WebSocketSession.nextOrNull(type: String): JsonObject? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text ?: continue).readText()
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]!!.jsonPrimitive.content != type) continue
            return message
        }
        return null
    }

    private suspend fun WebSocketSession.nextCount(): Long =
        next("data.mailbox.count")["unarchived"]!!.jsonPrimitive.content.toLong()

    private suspend fun WebSocketSession.nextCountOrNull(): Long? =
        nextOrNull("data.mailbox.count")?.get("unarchived")?.jsonPrimitive?.content?.toLong()

    private suspend fun WebSocketSession.nextGraph(): JsonObject = next("data.mail_graph")

    private suspend fun WebSocketSession.requestYear(year: Int) =
        send(Frame.Text("""{"type":"request.mail_graph","year":$year}"""))

    private suspend fun addMail(sentAt: Instant, subject: String) {
        database.query {
            Email.new {
                imapAccount = ImapAccount.all().first { it.user.id == signedIn.id }
                sender = EmailUser.all().first()
                senderName = "The Sender"
                this.subject = subject
                sent = sentAt
                rawContent = ByteArray(0)
            }
        }
    }

    @Test
    fun `the current year of the heatmap arrives without being asked for`() = testApplication {
        setUp()
        installRoute()

        val socket = openSocket()
        val graph = socket.nextGraph()

        assertEquals(currentYear, graph["year"]!!.jsonPrimitive.int)
        assertEquals(listOf(currentYear), graph["available_years"]!!.jsonArray.map { it.jsonPrimitive.int })
        // Three mails, all sent now, so one day carries all of them.
        assertEquals(mapOf(today to 3), graph.days())
        socket.close()
    }

    @Test
    fun `another year is sent once it is asked for`() = testApplication {
        setUp()
        installRoute()
        addMail(LocalDate(2020, 6, 15).atStartOfDayIn(TimeZone.UTC), "Old mail")

        val socket = openSocket()
        assertEquals(currentYear, socket.nextGraph()["year"]!!.jsonPrimitive.int)

        socket.requestYear(2020)
        val graph = socket.nextGraph()

        assertEquals(2020, graph["year"]!!.jsonPrimitive.int)
        assertEquals(mapOf("2020-06-15" to 1), graph.days())
        // Every year with mail in it travels with the answer, whichever year was asked for.
        assertEquals(listOf(2020, currentYear), graph["available_years"]!!.jsonArray.map { it.jsonPrimitive.int })
        socket.close()
    }

    @Test
    fun `a year on screen is updated when mail arrives`() = testApplication {
        setUp()
        installRoute()
        addMail(LocalDate(2020, 6, 15).atStartOfDayIn(TimeZone.UTC), "Old mail")

        val socket = openSocket()
        socket.nextGraph()
        socket.requestYear(2020)
        socket.nextGraph()

        addMail(LocalDate(2020, 6, 15).atStartOfDayIn(TimeZone.UTC), "Another old mail")
        mailboxNotifier.notifyMailboxChanged(signedIn.id.value)

        // Both years are re-read, so the one that changed comes back -- for 2020 that is the day
        // count, for the current year nothing changed and nothing is sent.
        val graph = socket.nextGraph()
        assertEquals(2020, graph["year"]!!.jsonPrimitive.int)
        assertEquals(mapOf("2020-06-15" to 2), graph.days())
        socket.close()
    }

    @Test
    fun `a year outside the calendar is ignored`() = testApplication {
        setUp()
        installRoute()

        val socket = openSocket()
        socket.nextGraph()

        socket.requestYear(12_345)

        assertNull(withTimeoutOrNull(2_000) { socket.nextOrNull("data.mail_graph") })
        socket.close()
    }

    private fun JsonObject.days(): Map<String, Int> =
        getValue("days").jsonObject.mapValues { (_, count) -> count.jsonPrimitive.int }

    private val currentYear get() = Clock.System.now().toLocalDateTime(TimeZone.UTC).year

    /** `yyyy-mm-dd` of the UTC day the fixture's mails were sent on. */
    private val today get() = Clock.System.now().toLocalDateTime(TimeZone.UTC).date.toString()

    private suspend fun archive(emailId: Uuid, action: EmailArchiveAction) {
        database.query {
            EmailArchive.new {
                this.email = Email.findById(emailId)!!
                this.action = action
                this.createdAt = Clock.System.now()
                this.createdByAgent = false
            }
        }
    }

    /** A user with an account and three mails, oldest first. */
    private suspend fun setUp(): List<Uuid> {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            signedIn = user

            val account = ImapAccount.new {
                this.user = user
                host = "imap.example.com"
                port = 993
                username = "owner"
                password = "secret"
            }
            val sender = EmailUser.new {
                this.user = user
                address = "sender@example.com"
            }

            (1..3).map { index ->
                Email.new {
                    imapAccount = account
                    this.sender = sender
                    senderName = "The Sender"
                    subject = "Mail $index"
                    sent = Clock.System.now()
                    rawContent = ByteArray(0)
                }.id.value
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json { encodeDefaults = true })
            }
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<MailboxNotifier> { mailboxNotifier }
            }
            routing {
                route("/api/webapp/home/socket") { homeSocket() }
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

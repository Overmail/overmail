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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
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

    /** The next count the server sends, or a failure if it stays quiet. */
    private suspend fun WebSocketSession.nextCount(): Long =
        withTimeout(5_000) { requireNotNull(nextCountOrNull()) { "no message from the socket" } }

    private suspend fun WebSocketSession.nextCountOrNull(): Long? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text ?: continue).readText()
            val message = Json.parseToJsonElement(text).jsonObject
            assertEquals("data.mailbox.count", message["type"]!!.jsonPrimitive.content)
            return message["unarchived"]!!.jsonPrimitive.content.toLong()
        }
        return null
    }

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

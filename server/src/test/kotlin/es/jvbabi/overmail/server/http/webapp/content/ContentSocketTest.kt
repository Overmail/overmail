package es.jvbabi.overmail.server.http.webapp.content

import es.jvbabi.overmail.server.data.avatar.AvatarLookup
import es.jvbabi.overmail.server.data.notifier.AvatarNotifier
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailLabel
import es.jvbabi.overmail.server.database.models.EmailRecipient
import es.jvbabi.overmail.server.database.models.EmailRecipientType
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.jobs.avatar.AvatarQueue
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The subscription a screen holds on the mails it shows. */
class ContentSocketTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:content-socket;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailNotifier = MailNotifier()

    private lateinit var signedIn: User
    private lateinit var stranger: User

    @Test
    fun `a subscription is answered with the metadata of the mail`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0])

        val mail = socket.nextEmails().single().jsonObject
        assertEquals(mails[0].toString(), mail["id"]!!.jsonPrimitive.content)
        assertEquals("Mail 1", mail["subject"]!!.jsonPrimitive.content)
        assertEquals("unarchive", mail["archive_state"]!!.jsonPrimitive.content)
        assertEquals(false, mail["is_read"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("sender@example.com", mail["sender"]!!.jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals(0, mail["labels"]!!.jsonArray.size)
        socket.close()
    }

    @Test
    fun `the metadata carries who the mail was addressed to`() = testApplication {
        val mails = setUp()
        installRoute()
        addRecipient(mails[0], "team@example.com", EmailRecipientType.RECIPIENT, "The Team")
        addRecipient(mails[0], "boss@example.com", EmailRecipientType.CC, null)

        val socket = openSocket()
        socket.subscribe(mails[0])

        val mail = socket.nextEmails().single().jsonObject
        val to = mail["to"]!!.jsonArray.single().jsonObject
        assertEquals("team@example.com", to["address"]!!.jsonPrimitive.content)
        assertEquals("The Team", to["name"]!!.jsonPrimitive.content)
        assertEquals("boss@example.com", mail["cc"]!!.jsonArray.single().jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals(0, mail["bcc"]!!.jsonArray.size)
        socket.close()
    }

    @Test
    fun `a picture found for the sender reaches the mails that show it`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0])
        val sender = socket.nextEmails().single().jsonObject["sender"]!!.jsonObject
        val senderId = Uuid.parse(sender["id"]!!.jsonPrimitive.content)

        // What the avatar queue announces once a lookup came back: the address changed, not the
        // mail -- and the socket has to work out which of the mails on screen that touches.
        mailNotifier.notifySenderChanged(signedIn.id.value, senderId)

        assertEquals(mails[0].toString(), socket.nextEmails().single().jsonObject["id"]!!.jsonPrimitive.content)
        socket.close()
    }

    @Test
    fun `a change to a subscribed mail is sent again`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0])
        socket.nextEmails()

        label(mails[0], "Studium")
        archive(mails[0], EmailArchiveAction.Archive)
        mailNotifier.notifyMailChanged(signedIn.id.value, mails[0])

        val mail = socket.nextEmails().single().jsonObject
        assertEquals("archive", mail["archive_state"]!!.jsonPrimitive.content)
        val label = mail["labels"]!!.jsonArray.single().jsonObject
        assertEquals("Studium", label["name"]!!.jsonPrimitive.content)
        socket.close()
    }

    @Test
    fun `several mails changing at once come in one message`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0], mails[1])
        assertEquals(2, socket.nextEmails().size)

        mailNotifier.notifyMailChanged(signedIn.id.value, mails[0])
        mailNotifier.notifyMailChanged(signedIn.id.value, mails[1])

        // Both within the debounce window, so the burst is one answer rather than two.
        assertEquals(2, socket.nextEmails().size)
        socket.close()
    }

    @Test
    fun `a mail nobody is subscribed to any more is not sent`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0])
        socket.nextEmails()

        socket.unsubscribe(mails[0])
        // Racing the unsubscribe would prove nothing, so the change comes after it was handled:
        // a second mail is subscribed and answered, which the socket only gets to afterwards.
        socket.subscribe(mails[1])
        socket.nextEmails()

        mailNotifier.notifyMailChanged(signedIn.id.value, mails[0])

        assertNull(withTimeoutOrNull(2_000) { socket.nextMessageOrNull("data.emails") })
        socket.close()
    }

    @Test
    fun `a mail of another user is unknown, exactly like one that does not exist`() = testApplication {
        setUp()
        installRoute()
        val foreign = database.query {
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
            }.id.value
        }
        val missing = Uuid.random()

        val socket = openSocket()
        socket.subscribe(foreign, missing)

        val unknown = socket.next("data.emails.unknown")["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf(foreign.toString(), missing.toString()), unknown.toSet())
        // And nothing about them, ever.
        assertNull(withTimeoutOrNull(1_000) { socket.nextMessageOrNull("data.emails") })
        socket.close()
    }

    @Test
    fun `subscribing again answers with a fresh snapshot`() = testApplication {
        val mails = setUp()
        installRoute()

        val socket = openSocket()
        socket.subscribe(mails[0])
        socket.nextEmails()

        // What a reconnected client does: it asks for the same ids and expects to be told where
        // things stand, not to wait for the next change.
        label(mails[0], "Rechnungen")
        socket.subscribe(mails[0])

        val mail = socket.nextEmails().single().jsonObject
        assertTrue(mail["labels"]!!.jsonArray.isNotEmpty())
        socket.close()
    }

    private suspend fun ApplicationTestBuilder.openSocket(): WebSocketSession =
        createClient { install(ClientWebSockets) }.webSocketSession("/api/webapp/content/socket")

    private suspend fun WebSocketSession.subscribe(vararg ids: Uuid) =
        send(Frame.Text("""{"type":"subscribe.emails","ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""))

    private suspend fun WebSocketSession.unsubscribe(vararg ids: Uuid) =
        send(Frame.Text("""{"type":"unsubscribe.emails","ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""))

    private suspend fun WebSocketSession.nextEmails() = next("data.emails")["emails"]!!.jsonArray

    private suspend fun WebSocketSession.next(type: String): JsonObject =
        withTimeout(5_000) { requireNotNull(nextMessageOrNull(type)) { "no $type from the socket" } }

    private suspend fun WebSocketSession.nextMessageOrNull(type: String): JsonObject? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text ?: continue).readText()
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]!!.jsonPrimitive.content != type) continue
            return message
        }
        return null
    }

    private suspend fun label(emailId: Uuid, name: String) {
        database.query {
            val label = Label.new {
                this.name = name
                color = "#ffffff"
                owner = signedIn
                createdAt = Clock.System.now()
                createdByAgent = false
            }
            EmailLabel.new {
                email = Email.findById(emailId)!!
                this.label = label
                labeledByAgent = false
                reason = null
            }
        }
    }

    private suspend fun addRecipient(
        emailId: Uuid,
        address: String,
        type: EmailRecipientType,
        name: String?,
    ) {
        database.query {
            EmailRecipient.new {
                email = Email.findById(emailId)!!
                emailUser = EmailUser.new {
                    user = signedIn
                    this.address = address
                }
                this.name = name
                this.type = type
            }
        }
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

    /** A user with an account and two mails. */
    private suspend fun setUp(): List<Uuid> {
        database.init()
        return database.query {
            signedIn = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Julius"
                lastname = "Babies"
            }
            stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
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

            (1..2).map { index ->
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
                provide<MailNotifier> { mailNotifier }
                // Nothing consumes it in the test: the senders of a batch are enqueued and stay
                // in the queue, which is all this socket does with it.
                provide<AvatarQueue> {
                    AvatarQueue(
                        database = database,
                        avatarLookup = AvatarLookup(),
                        avatarNotifier = AvatarNotifier(),
                        mailNotifier = mailNotifier,
                    )
                }
            }
            routing {
                route("/api/webapp/content/socket") { contentSocket() }
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

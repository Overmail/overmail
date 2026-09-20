package es.jvbabi.overmail.server.http.webapp.listing

import es.jvbabi.overmail.server.data.notifier.MailNotifier
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database

/** The index a listing is drawn from, and what keeps it current. */
class ListingSocketTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:listing-socket;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailNotifier = MailNotifier()

    private lateinit var signedIn: User
    private lateinit var account: ImapAccount
    /**
     * Named apart from the column it fills: inside `Email.new { }` the entity's own `sender` is
     * the nearer receiver, so a field called the same would be read off the half-built mail.
     */
    private lateinit var senderUser: EmailUser

    @Test
    fun `watching a listing answers with its shape`() = testApplication {
        setUp()
        mail("Today 1")
        mail("Today 2")
        installRoute()

        val socket = openSocket()
        socket.watch("by=day")

        val groups = socket.next("data.listing.groups")
        assertEquals(1, groups["token"]!!.jsonPrimitive.int())
        val group = groups["groups"]!!.jsonArray.single().jsonObject
        assertEquals(2, group["count"]!!.jsonPrimitive.int())
        socket.close()
    }

    @Test
    fun `a watched page answers with the mails at those offsets`() = testApplication {
        setUp()
        val first = mail("Newest", Clock.System.now())
        val second = mail("Older", Clock.System.now() - 1.days)
        installRoute()

        val socket = openSocket()
        // Ungrouped, so the whole listing is one group and the page is the listing.
        socket.watch("")
        socket.next("data.listing.groups")
        socket.watchPages("")

        val page = socket.next("data.listing.page")
        assertEquals(2, page["total"]!!.jsonPrimitive.int())
        assertEquals(
            listOf(first.toString(), second.toString()),
            page["ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        socket.close()
    }

    @Test
    fun `a mail arriving reaches the listing without anybody asking`() = testApplication {
        setUp()
        mail("The one that was here")
        installRoute()

        val socket = openSocket()
        socket.watch("")
        assertEquals(1, socket.next("data.listing.groups").count())
        socket.watchPages("")
        assertEquals(1, socket.next("data.listing.page")["ids"]!!.jsonArray.size)

        // What the importer does: the mail is in, and it says so.
        val arrived = mail("Just arrived")
        mailNotifier.notifyMailChanged(signedIn.id.value, arrived, movedListings = true)

        // Nothing was sent from this side in between -- the shape and the page come on their own.
        assertEquals(2, socket.next("data.listing.groups").count())
        val page = socket.next("data.listing.page")
        assertTrue(page["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(arrived.toString()))
        socket.close()
    }

    @Test
    fun `a mail leaving the filter leaves the listing`() = testApplication {
        setUp()
        val kept = mail("Stays")
        val archived = mail("Goes")
        installRoute()

        val socket = openSocket()
        // The mailbox: what has not been put away.
        socket.watch("archived_state=Unarchive")
        assertEquals(2, socket.next("data.listing.groups").count())
        socket.watchPages("")
        assertEquals(2, socket.next("data.listing.page")["ids"]!!.jsonArray.size)

        archive(archived)
        mailNotifier.notifyMailChanged(signedIn.id.value, archived, movedListings = true)

        assertEquals(1, socket.next("data.listing.groups").count())
        assertEquals(
            listOf(kept.toString()),
            socket.next("data.listing.page")["ids"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        socket.close()
    }

    @Test
    fun `a change that moved nothing is not answered`() = testApplication {
        setUp()
        val only = mail("The one")
        installRoute()

        val socket = openSocket()
        socket.watch("")
        socket.next("data.listing.groups")
        socket.watchPages("")
        socket.next("data.listing.page")

        // A flag on the mail: every listing still holds the same mails in the same order, and
        // the mail itself travels over the content socket.
        mailNotifier.notifyMailChanged(signedIn.id.value, only, movedListings = false)

        assertNull(withTimeoutOrNull(2_000) { socket.nextMessageOrNull("data.listing.groups") })
        socket.close()
    }

    @Test
    fun `an answer that did not change is not sent again`() = testApplication {
        setUp()
        val other = mail("Somebody else's business")
        installRoute()

        val socket = openSocket()
        socket.watch("")
        socket.next("data.listing.groups")

        // Moved, so this socket looks again -- and finds the same listing it already sent. A
        // mail read in another tab must not repaint every table this user has open.
        mailNotifier.notifyMailChanged(signedIn.id.value, other, movedListings = true)
        assertNull(withTimeoutOrNull(2_500) { socket.nextMessageOrNull("data.listing.groups") })

        // The next real change still arrives, so nothing was swallowed for good.
        mail("Actually new")
        mailNotifier.notifyMailChanged(signedIn.id.value, other, movedListings = true)
        assertEquals(2, socket.next("data.listing.groups").count())
        socket.close()
    }

    @Test
    fun `every answer carries the token of the watch it is about`() = testApplication {
        setUp()
        mail("Unread one")
        installRoute()

        val socket = openSocket()
        socket.watch("", token = 7)
        assertEquals(7, socket.next("data.listing.groups")["token"]!!.jsonPrimitive.int())

        socket.watch("read_state=true", token = 8)
        val second = socket.next("data.listing.groups")
        assertEquals(8, second["token"]!!.jsonPrimitive.int())
        // Nothing is read, so the other listing is empty -- which is a different answer, not a
        // missing one.
        assertEquals(0, second.count())
        socket.close()
    }

    @Test
    fun `a listing that cannot be read is an error, and the socket stays up`() = testApplication {
        setUp()
        mail("The one")
        installRoute()

        val socket = openSocket()
        socket.watch("has_labels=not-an-id")

        val error = socket.next("data.listing.failed")["error"]!!.jsonObject
        assertEquals("invalid_request", error["code"]!!.jsonPrimitive.content)

        // Still answering: what was wrong was the message, not the connection.
        socket.watch("", token = 2)
        assertEquals(1, socket.next("data.listing.groups").count())
        socket.close()
    }

    @Test
    fun `another user's mail is no part of the listing`() = testApplication {
        setUp()
        mail("Mine")
        installRoute()

        val stranger = database.query {
            val other = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Some"
                lastname = "One"
            }
            val theirAccount = ImapAccount.new {
                user = other
                host = "imap.example.com"
                port = 993
                username = "stranger"
                password = "secret"
            }
            Email.new {
                imapAccount = theirAccount
                this.sender = EmailUser.new {
                    user = other
                    address = "someone@example.com"
                }
                senderName = null
                subject = "Not yours"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
            }.id.value
        }

        val socket = openSocket()
        socket.watch("")
        socket.watchPages("")

        assertEquals(1, socket.next("data.listing.groups").count())
        val ids = socket.next("data.listing.page")["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, ids.size)
        assertTrue(stranger.toString() !in ids)
        socket.close()
    }

    /** How much mail the one group of an ungrouped listing holds. */
    private fun JsonObject.count(): Int =
        this["groups"]!!.jsonArray.sumOf { group -> group.jsonObject["count"]!!.jsonPrimitive.int() }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toDouble().toInt()

    private suspend fun ApplicationTestBuilder.openSocket(): WebSocketSession =
        createClient { install(ClientWebSockets) }.webSocketSession("/api/webapp/listing/socket")

    private suspend fun WebSocketSession.watch(query: String, token: Int = 1) =
        send(Frame.Text("""{"type":"watch.listing","query":"$query","token":$token}"""))

    private suspend fun WebSocketSession.watchPages(group: String, offset: Int = 0) =
        send(
            Frame.Text(
                """{"type":"watch.pages","pages":[{"group":"$group","offset":$offset,"limit":100}]}"""
            )
        )

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

    private suspend fun mail(subject: String, sent: kotlin.time.Instant = Clock.System.now()): Uuid =
        database.query {
            Email.new {
                imapAccount = account
                sender = senderUser
                senderName = "The Sender"
                this.subject = subject
                this.sent = sent
                rawContent = ByteArray(0)
            }.id.value
        }

    private suspend fun archive(emailId: Uuid) {
        database.query {
            EmailArchive.new {
                email = Email.findById(emailId)!!
                action = EmailArchiveAction.Archive
                createdAt = Clock.System.now()
                createdByAgent = false
            }
        }
    }

    /** A user with an account to hang mail off. */
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
            senderUser = EmailUser.new {
                user = signedIn
                address = "sender@example.com"
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
            }
            routing {
                route("/api/webapp/listing/socket") { listingSocket() }
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

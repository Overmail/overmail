package es.jvbabi.overmail.server.http.email.list

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.client.plugins.sse.sse
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** The ids of a listing as a stream: what a client with a cache of its own follows. */
class EmailListIdsStreamTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-list-ids-stream;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailNotifier = MailNotifier()

    private lateinit var signedIn: User
    private lateinit var account: ImapAccount
    private lateinit var sender: EmailUser

    @Test
    fun `the stream opens with every group and its ids`() = runBlocking {
        setUp()
        val today = mail(daysBack = 0)
        val older = listOf(mail(daysBack = 3), mail(daysBack = 3))

        follow("/api/emails/list/ids/stream?by=day") { events ->
            val snapshot = events.next("snapshot")

            assertEquals(listOf("day"), snapshot.getValue("groupings").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                setOf(listOf(today), older.reversed()).map { ids -> ids.map { it.toString() } }.toSet(),
                snapshot.groups().map { it.ids() }.toSet(),
            )
        }
    }

    @Test
    fun `a mail arriving sends the listing again`() = runBlocking {
        setUp()
        val here = mail(daysBack = 1)

        follow("/api/emails/list/ids/stream") { events ->
            assertEquals(listOf(here.toString()), events.next("snapshot").groups().single().ids())

            // What the importer does: the mail is in, and it says so.
            val arrived = mail(daysBack = 0)
            mailNotifier.notifyMailChanged(signedIn.id.value, arrived, movedListings = true)

            val group = events.next("snapshot").groups().single()
            assertEquals(listOf(arrived, here).map { it.toString() }, group.ids())
            assertEquals(2, group.getValue("total").jsonPrimitive.long)
        }
    }

    @Test
    fun `a flag moves a listing that filters by it`() = runBlocking {
        setUp()
        val kept = mail(daysBack = 0)
        val read = mail(daysBack = 1)

        follow("/api/emails/list/ids/stream?read_state=false") { events ->
            assertEquals(2, events.next("snapshot").groups().single().ids().size)

            // Not a move to the notifier -- but the unread listing no longer holds it.
            database.query { Email.findById(read)!!.isRead = true }
            mailNotifier.notifyMailChanged(signedIn.id.value, read, movedListings = false)

            assertEquals(listOf(kept.toString()), events.next("snapshot").groups().single().ids())
        }
    }

    @Test
    fun `a change that leaves the ids as they were is not sent`() = runBlocking {
        setUp()
        val only = mail(daysBack = 0)

        follow("/api/emails/list/ids/stream") { events ->
            events.next("snapshot")

            mailNotifier.notifyMailChanged(signedIn.id.value, only, movedListings = true)

            assertNull(withTimeoutOrNull(2_000) { events.receive() })
        }
    }

    @Test
    fun `a client leaving ends the stream`() = runBlocking {
        setUp()
        mail(daysBack = 0)

        val subscribers = (mailNotifier.subscribe(signedIn.id.value) as MutableSharedFlow<*>).subscriptionCount

        follow(
            path = "/api/emails/list/ids/stream",
            afterLeaving = {
                // Nothing changes after the client left -- only the keep-alive can find out, and
                // the stream letting go of the notifier is how it shows.
                withTimeout(5_000) { subscribers.first { it == 0 } }
            },
        ) { events ->
            events.next("snapshot")
            assertEquals(1, subscribers.value)
        }
    }

    @Test
    fun `a parameter that cannot be read ends the stream with an error`() = runBlocking {
        setUp()

        follow("/api/emails/list/ids/stream?by=nonsense") { events ->
            val failed = events.next("failed")
            assertEquals(400, failed.getValue("error").jsonObject.getValue("status").jsonPrimitive.long.toInt())
        }
    }

    /**
     * Opens the stream on a real server and hands [block] its events, parsed, in the order they
     * arrived. Not `testApplication`: its engine hands a response to the client only once it is
     * complete, and this one never is. [afterLeaving] runs once the client is gone, while the
     * server still is not.
     */
    private suspend fun follow(
        path: String,
        afterLeaving: suspend () -> Unit = {},
        block: suspend (Channel<JsonObject>) -> Unit,
    ) {
        val server = embeddedServer(Netty, port = 0) { module() }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { install(ClientSSE) }

        try {
            client.sse("http://127.0.0.1:$port$path") {
                val events = Channel<JsonObject>(Channel.UNLIMITED)
                val reading = launch {
                    incoming.collect { event ->
                        event.data?.let { events.send(Json.parseToJsonElement(it).jsonObject) }
                    }
                }
                block(events)
                reading.cancel()
            }
            client.close()
            afterLeaving()
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private suspend fun Channel<JsonObject>.next(type: String): JsonObject {
        val event = withTimeout(5_000) { receive() }
        assertEquals(type, event.getValue("type").jsonPrimitive.content)
        return event
    }

    private fun JsonObject.groups() = getValue("groups").jsonArray.map { it.jsonObject }

    private fun JsonObject.ids() = getValue("ids").jsonArray.map { it.jsonPrimitive.content }

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
                address = "sender-${Uuid.random()}@example.com"
            }
        }
    }

    /** A mail sent [daysBack] days ago; of two on the same day, the later one is newer. */
    private suspend fun mail(daysBack: Int): Uuid = database.query {
        Email.new {
            imapAccount = account
            sender = this@EmailListIdsStreamTest.sender
            senderName = "The Sender"
            subject = "Mail"
            sent = Clock.System.now() - daysBack.days
            rawContent = ByteArray(0)
        }.id.value
    }

    private fun Application.module() {
        install(SSE)
        installApiErrorHandling()
        install(Authentication) { alwaysSignedIn() }
        dependencies {
            provide<OvermailDatabase> { database }
            provide<MailNotifier> { mailNotifier }
        }
        routing {
            route("/api/emails/list/ids/stream") { emailListIdsStream(keepAlive = 100.milliseconds) }
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

package es.jvbabi.overmail.server.http.email.changes

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
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** Every change to a user's mail as a stream: what a client with a database of its own applies. */
class EmailChangesTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:email-changes;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailNotifier = MailNotifier()

    private lateinit var signedIn: User
    private lateinit var account: ImapAccount
    private lateinit var sender: EmailUser

    @Test
    fun `a flag goes out as the whole mail`() = runBlocking {
        setUp()
        val mail = mail()

        follow { events ->
            // Not a move -- a client holding the mail needs it all the same.
            database.query { Email.findById(mail)!!.isRead = true }
            mailNotifier.notifyMailChanged(signedIn.id.value, mail, movedListings = false)

            val email = events.next("emails").getValue("emails").jsonArray.single().jsonObject
            assertEquals(mail.toString(), email.getValue("id").jsonPrimitive.content)
            assertEquals(true, email.getValue("is_read").jsonPrimitive.boolean)
            assertEquals(account.id.value.toString(), email.getValue("imap_account_id").jsonPrimitive.content)
        }
    }

    @Test
    fun `a burst of changes is one event`() = runBlocking {
        setUp()
        val first = mail()
        val second = mail()

        follow { events ->
            mailNotifier.notifyMailChanged(signedIn.id.value, first, movedListings = true)
            mailNotifier.notifyMailChanged(signedIn.id.value, second, movedListings = true)
            mailNotifier.notifyMailChanged(signedIn.id.value, first, movedListings = false)

            val ids = events.next("emails").getValue("emails").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(setOf(first, second).map { it.toString() }.toSet(), ids.toSet())
        }
    }

    @Test
    fun `a mail that is gone goes out as removed`() = runBlocking {
        setUp()
        val mail = mail()

        follow { events ->
            database.query { Email.findById(mail)!!.delete() }
            mailNotifier.notifyMailChanged(signedIn.id.value, mail, movedListings = true)

            assertEquals(
                listOf(mail.toString()),
                events.next("removed").getValue("ids").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `a correspondent whose picture changed goes out as a sender`() = runBlocking {
        setUp()

        follow { events ->
            mailNotifier.notifySenderChanged(signedIn.id.value, sender.id.value)

            val changed = events.next("senders").getValue("senders").jsonArray.single().jsonObject
            assertEquals(sender.id.value.toString(), changed.getValue("id").jsonPrimitive.content)
            assertEquals(sender.address, changed.getValue("address").jsonPrimitive.content)
        }
    }

    @Test
    fun `a client leaving ends the stream`() = runBlocking {
        setUp()

        follow(
            afterLeaving = {
                // Nothing changes after the client left -- only the keep-alive can find out, and
                // the stream letting go of the notifier is how it shows.
                withTimeout(5_000) { subscribers().first { it == 0 } }
            },
        ) { }
    }

    private fun subscribers(): StateFlow<Int> =
        (mailNotifier.subscribe(signedIn.id.value) as MutableSharedFlow<*>).subscriptionCount

    /**
     * Opens the stream on a real server and hands [block] its events, parsed, in the order they
     * arrived -- once `ready` said the stream listens. Not
     * `testApplication`: its engine hands a response to the client only once it is complete, and
     * this one never is. [afterLeaving] runs once the client is gone, while the server still is not.
     */
    private suspend fun follow(
        afterLeaving: suspend () -> Unit = {},
        block: suspend (Channel<JsonObject>) -> Unit,
    ) {
        val server = embeddedServer(Netty, port = 0) { module() }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { install(ClientSSE) }

        try {
            client.sse("http://127.0.0.1:$port/api/emails/changes") {
                val events = Channel<JsonObject>(Channel.UNLIMITED)
                val reading = launch {
                    incoming.collect { event ->
                        event.data?.let { events.send(Json.parseToJsonElement(it).jsonObject) }
                    }
                }
                events.next("ready")
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

    private suspend fun mail(): Uuid = database.query {
        Email.new {
            imapAccount = account
            sender = this@EmailChangesTest.sender
            senderName = "The Sender"
            subject = "Mail"
            sent = Clock.System.now()
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
            route("/api/emails/changes") { emailChanges(keepAlive = 100.milliseconds) }
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

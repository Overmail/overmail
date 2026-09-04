package es.jvbabi.overmail.server.http.stack

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.classification.EmailClassification
import es.jvbabi.overmail.server.ai.classification.EmailClassificationQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.config.EmailConfig
import es.jvbabi.overmail.server.config.SmtpConfig
import es.jvbabi.overmail.server.database.DatabaseConfig
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.MailEvent
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailArchive
import es.jvbabi.overmail.server.database.models.EmailArchiveAction
import es.jvbabi.overmail.server.database.models.EmailArchives
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * The pile, which since the content socket is only a question of membership: which mails, in what
 * order. What a card shows is subscribed per mail elsewhere.
 */
class StackSocketTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:stack-socket;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val mailNotifier = MailNotifier()

    private lateinit var signedIn: User

    private val testModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    /** Nothing in here is reached: no lookup, no mail, no second database. */
    private val testConfig = ApplicationConfig(
        baseUrl = "http://localhost",
        database = DatabaseConfig(host = "localhost", database = "none", user = "none", password = "none"),
        email = EmailConfig(
            smtp = SmtpConfig(
                host = "localhost",
                port = 25,
                auth = SmtpConfig.Auth(username = "none", password = "none"),
            )
        ),
        ai = ApplicationConfig.AiConfig(apiKey = "none", model = "test-model", baseUrl = "http://localhost:1"),
    )

    @Test
    fun `hands out ids newest first, archived mails left out`() = testApplication {
        val mails = setUp(count = 4)
        installRoute()
        archive(mails[1], EmailArchiveAction.Archive)
        archive(mails[2], EmailArchiveAction.Spam)

        val socket = openSocket()

        // mails[0] is the newest, and the two in between are out of the mailbox.
        assertEquals(listOf(mails[0], mails[3]).map { it.toString() }, socket.nextIds())
        socket.close()
    }

    @Test
    fun `asking again continues below the last batch`() = testApplication {
        val mails = setUp(count = 12)
        installRoute()

        val socket = openSocket()
        val first = socket.nextIds()
        assertEquals(mails.take(10).map { it.toString() }, first)

        socket.send(Frame.Text("""{"type":"request.emails"}"""))

        // The cursor walks backwards through send time and includes its own second, so the
        // second batch starts again with the mail the first one ended on -- what that buys is
        // that mails sharing a send second are never skipped. A client dedupes by id.
        assertEquals(mails.drop(9).map { it.toString() }, socket.nextIds())
        socket.close()
    }

    @Test
    fun `archiving writes an event and announces the mail`() = testApplication {
        val mails = setUp(count = 1)
        installRoute()

        val socket = openSocket()
        socket.nextIds()

        // Listening before the request, or the announcement would be gone before this gets here.
        val announced = CompletableDeferred<Uuid>()
        val events = mailNotifier.subscribe(signedIn.id.value)
        val collector = CoroutineScope(Dispatchers.Default).launch {
            events.collect { event -> (event as? MailEvent.Changed)?.let { announced.complete(it.emailId) } }
        }

        socket.send(Frame.Text("""{"type":"update.email.archive","email_id":"${mails[0]}"}"""))

        assertEquals(mails[0], withTimeout(5_000) { announced.await() })
        assertEquals(
            EmailArchiveAction.Archive,
            database.query {
                EmailArchives.select(EmailArchives.action)
                    .where { EmailArchives.email eq mails[0] }
                    .single()[EmailArchives.action]
            },
        )
        collector.cancel()
        socket.close()
    }

    private suspend fun ApplicationTestBuilder.openSocket(): WebSocketSession =
        createClient { install(ClientWebSockets) }.webSocketSession("/api/stack")

    private suspend fun WebSocketSession.nextIds(): List<String> {
        for (frame in incoming) {
            val text = (frame as? Frame.Text ?: continue).readText()
            val message = Json.parseToJsonElement(text).jsonObject
            if (message["type"]!!.jsonPrimitive.content != "data.emails") continue
            return message["email_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        }
        error("no batch from the socket")
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
                    // A day apart, so "newest first" is an order and not a coincidence.
                    sent = now - index.days
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
                // Nothing consumes it here: the look-ahead enqueues and the ids stay in the
                // queue, so the classification behind it is never asked to do anything.
                provide<EmailClassificationQueue> {
                    EmailClassificationQueue(
                        emailClassification = EmailClassification(
                            config = testConfig,
                            model = testModel,
                            overmailDatabase = database,
                            mailNotifier = mailNotifier,
                            knowledgeStore = KnowledgeStore(database),
                        ),
                        database = database,
                    )
                }
            }
            routing {
                route("/api/stack") { stackSocket() }
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

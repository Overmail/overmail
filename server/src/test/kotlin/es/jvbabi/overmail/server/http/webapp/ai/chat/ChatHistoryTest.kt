package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.User
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Drives the endpoint through the real routing pipeline against H2. The session provider is
 * replaced by one that always authenticates [signedIn]: what is under test here is the payload,
 * not the sign-in.
 */
class ChatHistoryTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:chat-history;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private lateinit var signedIn: User

    @Test
    fun `history resolves the references of a sent prompt`() = testApplication {
        val fixture = setUpFixture()

        application {
            install(ContentNegotiation) { json() }
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<AiChatStreamNotifier> { AiChatStreamNotifier() }
            }
            routing {
                route("/api/webapp/ai/chat/{chatId}/history") { chatHistory() }
            }
        }

        val response = client.get("/api/webapp/ai/chat/${fixture.chatId}/history")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)

        val segments = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals("Invoice 42", segments[0].jsonObject["subject"]!!.jsonPrimitive.content)
        assertEquals(" and ", segments[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Bills", segments[2].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("sender@example.com", segments[3].jsonObject["address"]!!.jsonPrimitive.content)
        // Never resolved: the id belongs to nothing the user owns.
        assertEquals(JsonNull, segments[4].jsonObject["subject"])

        val answer = messages[1].jsonObject
        assertEquals("assistant", answer["type"]!!.jsonPrimitive.content)
        assertEquals(true, answer["pending"]!!.jsonPrimitive.content.toBoolean())
    }

    private data class Fixture(val chatId: Uuid)

    private suspend fun setUpFixture(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
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
            val email = Email.new {
                imapAccount = account
                this.sender = sender
                senderName = "The Sender"
                subject = "Invoice 42"
                sent = Clock.System.now()
                rawContent = ByteArray(0)
                textContent = "Please pay."
            }
            val label = Label.new {
                name = "Bills"
                color = "#ffffff"
                owner = user
                createdAt = Clock.System.now()
                createdByAgent = false
            }
            val chat = AiChat.new {
                this.user = user
                name = null
                nameSetByUser = false
                createdAt = Clock.System.now()
            }

            AiChatMessage.new {
                this.chat = chat
                this.sender = AiChatMessageSender.USER
                sentAt = Clock.System.now()
                finishedAt = Clock.System.now()
                content = AiChatMessage.MessageContent.UserMessageContent(
                    segments = listOf(
                        AiChatMessage.MessageContent.UserMessageContent.Segment.Email(email.id.value),
                        AiChatMessage.MessageContent.UserMessageContent.Segment.Text(" and "),
                        AiChatMessage.MessageContent.UserMessageContent.Segment.Label(label.id.value),
                        AiChatMessage.MessageContent.UserMessageContent.Segment.Sender(sender.id.value),
                        AiChatMessage.MessageContent.UserMessageContent.Segment.Email(Uuid.random()),
                    )
                )
            }
            AiChatMessage.new {
                this.chat = chat
                this.sender = AiChatMessageSender.AGENT
                sentAt = Clock.System.now()
                finishedAt = null
                content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = "test-model", tokensOutput = 0)
            }

            Fixture(chatId = chat.id.value)
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

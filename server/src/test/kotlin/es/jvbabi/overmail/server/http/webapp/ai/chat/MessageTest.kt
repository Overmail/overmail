package es.jvbabi.overmail.server.http.webapp.ai.chat

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgent
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.AiChatMessages
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MessageTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:chat-message;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    private lateinit var signedIn: User

    @Test
    fun `a second message goes into the same chat`() = testApplication {
        setUpUser()
        installRoute()

        val first = send(chatId = null, text = "Was habe ich von der Uni?")
        val chatId = first["chat_id"]!!.jsonPrimitive.content

        val second = send(chatId = chatId, text = "Und von wem war die erste?")
        assertEquals(chatId, second["chat_id"]!!.jsonPrimitive.content)

        // Question, answer, question, answer -- and only the last answer is still open, so the
        // next run has the whole exchange as its history.
        val messages = database.query {
            AiChatMessage
                .find { AiChatMessages.chatId eq Uuid.parse(chatId) }
                .orderBy(AiChatMessages.sentAt to SortOrder.ASC)
                .map { message -> message.sender to (message.finishedAt == null) }
        }

        assertEquals(
            listOf(
                AiChatMessageSender.USER to false,
                AiChatMessageSender.AGENT to true,
                AiChatMessageSender.USER to false,
                AiChatMessageSender.AGENT to true,
            ),
            messages,
        )
    }

    @Test
    fun `a chat of another user cannot be written to`() = testApplication {
        setUpUser()
        installRoute()

        val foreignChat = database.query {
            val stranger = User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
            AiChat.new {
                user = stranger
                name = null
                nameSetByUser = false
                createdAt = kotlin.time.Clock.System.now()
            }.id.value
        }

        val response = client.post("/api/webapp/ai/chat") {
            contentType(ContentType.Application.Json)
            setBody(body(chatId = foreignChat.toString(), text = "hi"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // Nothing was written into the foreign chat.
        assertNull(database.query { AiChatMessage.find { AiChatMessages.chatId eq foreignChat }.firstOrNull() })
    }

    private suspend fun ApplicationTestBuilder.send(chatId: String?, text: String) =
        Json.parseToJsonElement(
            client.post("/api/webapp/ai/chat") {
                contentType(ContentType.Application.Json)
                setBody(body(chatId, text))
            }.bodyAsText()
        ).jsonObject

    private fun body(chatId: String?, text: String) = """
        {
          "chat_id": ${chatId?.let { "\"$it\"" } ?: "null"},
          "prompt": {"type": "normal", "segments": [{"type": "text", "content": "$text"}]}
        }
    """.trimIndent()

    private suspend fun setUpUser() {
        database.init()
        signedIn = database.query {
            User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
        }
    }

    private fun ApplicationTestBuilder.installRoute() {
        application {
            install(ContentNegotiation) { json() }
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<LLModel> { model }
                provide<AiChatNotifier> { AiChatNotifier() }
                provide<AiChatStreamNotifier> { AiChatStreamNotifier() }
                provide<ChatAgent> {
                    ChatAgent(
                        config = ApplicationConfig.AiConfig("none", model.id, "http://localhost:1"),
                        model = model,
                        database = database,
                        streamNotifier = resolve(),
                        chatNotifier = resolve(),
                        mailNotifier = MailNotifier(),
                        knowledgeStore = KnowledgeStore(resolve()),
                    )
                }
                // No consumer in the test: the runs are queued and stay there.
                provide<ChatAgentQueue> { ChatAgentQueue(chatAgent = resolve(), streamNotifier = resolve()) }
            }
            routing {
                route("/api/webapp/ai/chat") { message() }
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

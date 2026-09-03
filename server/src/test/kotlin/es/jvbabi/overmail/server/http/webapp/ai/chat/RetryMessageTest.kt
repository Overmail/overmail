package es.jvbabi.overmail.server.http.webapp.ai.chat

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.ChatAgent
import es.jvbabi.overmail.server.ai.chat.ChatAgentQueue
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.User
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class RetryMessageTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:retry-message;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    private lateinit var signedIn: User

    @Test
    fun `a finished answer is emptied and queued again`() = testApplication {
        val fixture = setUpFixture()
        installRoute()

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/retry")
        assertEquals(HttpStatusCode.Accepted, response.status)

        database.query {
            val message = AiChatMessage.findById(fixture.answerId)!!
            assertNull(message.finishedAt)
            assertEquals(
                "",
                (message.content as AiChatMessage.MessageContent.AgentMessageContent).text,
            )
        }
    }

    @Test
    fun `a running answer is not restarted`() = testApplication {
        val fixture = setUpFixture()
        installRoute()
        database.query { AiChatMessage.findById(fixture.answerId)!!.finishedAt = null }

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/retry")
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `a user message cannot be retried`() = testApplication {
        val fixture = setUpFixture()
        installRoute()

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.questionId}/retry")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a chat of another user cannot be retried`() = testApplication {
        val fixture = setUpFixture()
        installRoute()
        signedIn = database.query {
            User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
            }
        }

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/retry")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoute() {
        application {
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<LLModel> { model }
                provide<AiChatStreamNotifier> { AiChatStreamNotifier() }
                provide<ChatAgent> {
                    ChatAgent(
                        config = ApplicationConfig.AiConfig("none", model.id, "http://localhost:1"),
                        model = model,
                        database = database,
                        streamNotifier = resolve(),
                        chatNotifier = AiChatNotifier(),
                    )
                }
                // No consumer in the test: the run is queued and stays there.
                provide<ChatAgentQueue> { ChatAgentQueue(chatAgent = resolve()) }
            }
            routing {
                route("/api/webapp/ai/chat/{chatId}/message/{messageId}/retry") { retryMessage() }
            }
        }
    }

    private data class Fixture(val chatId: Uuid, val questionId: Uuid, val answerId: Uuid)

    private suspend fun setUpFixture(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
            }
            signedIn = user

            val chat = AiChat.new {
                this.user = user
                name = null
                nameSetByUser = false
                createdAt = Clock.System.now()
            }
            val question = AiChatMessage.new {
                this.chat = chat
                sender = AiChatMessageSender.USER
                sentAt = Clock.System.now()
                finishedAt = Clock.System.now()
                content = AiChatMessage.MessageContent.UserMessageContent(
                    segments = listOf(AiChatMessage.MessageContent.UserMessageContent.Segment.Text("hi"))
                )
            }
            val answer = AiChatMessage.new {
                this.chat = chat
                sender = AiChatMessageSender.AGENT
                sentAt = Clock.System.now()
                finishedAt = Clock.System.now()
                content = AiChatMessage.MessageContent.AgentMessageContent(text = "old answer", model = model.id)
            }

            Fixture(chatId = chat.id.value, questionId = question.id.value, answerId = answer.id.value)
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

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
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.http.api.installApiErrorHandling
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The stop endpoint: who may call it and what it answers. What a stopped run leaves behind is the
 * queue's and the agent's business, see `ChatAgentQueueTest`.
 */
class StopMessageTest {

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:stop-message;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    private val streamNotifier = AiChatStreamNotifier()

    private val queue = ChatAgentQueue(
        chatAgent = ChatAgent(
            config = ApplicationConfig.AiConfig("none", model.id, "http://localhost:1"),
            model = model,
            database = database,
            streamNotifier = streamNotifier,
            chatNotifier = AiChatNotifier(),
            mailNotifier = MailNotifier(),
            knowledgeStore = KnowledgeStore(database),
        ),
        streamNotifier = streamNotifier,
    )

    private lateinit var signedIn: User

    @Test
    fun `a waiting answer is finished as stopped, and stopping it again is not an error`() = testApplication {
        val fixture = setUpFixture(answerFinished = false)
        installRoute()
        // No consumer in the test: the run is queued and stays there.
        queue.enqueue(fixture.answerId)
        val stream = assertNotNull(streamNotifier.of(fixture.answerId))

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/stop")
        assertEquals(HttpStatusCode.NoContent, response.status)

        database.query {
            val message = AiChatMessage.findById(fixture.answerId)!!
            assertNotNull(message.finishedAt)
            assertTrue((message.content as AiChatMessage.MessageContent.AgentMessageContent).stopped)
        }
        assertTrue(stream.snapshot().completed)
        assertNull(streamNotifier.of(fixture.answerId))

        val again = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/stop")
        assertEquals(HttpStatusCode.NoContent, again.status)
    }

    @Test
    fun `a finished answer is left as it is`() = testApplication {
        val fixture = setUpFixture()
        installRoute()

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/stop")
        assertEquals(HttpStatusCode.NoContent, response.status)

        database.query {
            val content = AiChatMessage.findById(fixture.answerId)!!.content as AiChatMessage.MessageContent.AgentMessageContent
            assertEquals("old answer", content.text)
            // It ran to its end, and pressing stop too late does not change that.
            assertFalse(content.stopped)
        }
    }

    @Test
    fun `an answer left pending by a restart is finished as stopped`() = testApplication {
        val fixture = setUpFixture(answerFinished = false)
        installRoute()

        // Neither queued nor running: nobody will ever write it, and without this the client
        // would show it as pending for good.
        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/stop")
        assertEquals(HttpStatusCode.NoContent, response.status)

        database.query {
            val message = AiChatMessage.findById(fixture.answerId)!!
            assertNotNull(message.finishedAt)
            assertTrue((message.content as AiChatMessage.MessageContent.AgentMessageContent).stopped)
        }
    }

    @Test
    fun `a user message cannot be stopped`() = testApplication {
        val fixture = setUpFixture()
        installRoute()

        // It exists and is the caller's -- it is just not an answer, which is a conflict, not a miss.
        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.questionId}/stop")
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `an unknown message is not found`() = testApplication {
        val fixture = setUpFixture()
        installRoute()

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${Uuid.random()}/stop")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `an answer in a chat of another user cannot be stopped`() = testApplication {
        val fixture = setUpFixture(answerFinished = false)
        installRoute()
        queue.enqueue(fixture.answerId)
        signedIn = database.query {
            User.new {
                username = "stranger-${Uuid.random()}"
                email = "stranger-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
        }

        val response = client.post("/api/webapp/ai/chat/${fixture.chatId}/message/${fixture.answerId}/stop")
        assertEquals(HttpStatusCode.Forbidden, response.status)

        // And it is still being waited for.
        assertNull(database.query { AiChatMessage.findById(fixture.answerId)!!.finishedAt })
        assertNotNull(streamNotifier.of(fixture.answerId))
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoute() {
        application {
            installApiErrorHandling()
            install(Authentication) { alwaysSignedIn() }
            dependencies {
                provide<OvermailDatabase> { database }
                provide<ChatAgentQueue> { queue }
            }
            routing {
                route("/api/webapp/ai/chat/{chatId}/message/{messageId}/stop") { stopMessage() }
            }
        }
    }

    private data class Fixture(val chatId: Uuid, val questionId: Uuid, val answerId: Uuid)

    private suspend fun setUpFixture(answerFinished: Boolean = true): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
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
                finishedAt = if (answerFinished) Clock.System.now() else null
                content = AiChatMessage.MessageContent.AgentMessageContent(
                    text = if (answerFinished) "old answer" else "",
                    model = model.id,
                    tokensOutput = 0,
                )
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

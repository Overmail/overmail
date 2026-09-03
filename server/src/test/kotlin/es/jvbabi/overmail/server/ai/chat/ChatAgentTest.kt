package es.jvbabi.overmail.server.ai.chat

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.Clock

class ChatAgentTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion),
    )

    @Test
    fun `run without a user message does not call the model`() = runTest {
        val streamNotifier = AiChatStreamNotifier()
        val database = OvermailDatabase(
            Database.connect("jdbc:h2:mem:chat-agent;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        )
        database.init()

        val agent = ChatAgent(
            config = ApplicationConfig.AiConfig(apiKey = "none", model = model.id, baseUrl = "http://localhost:1"),
            model = model,
            database = database,
            streamNotifier = streamNotifier,
            chatNotifier = AiChatNotifier(),
        )

        val messageId = database.query {
            val user = User.new {
                username = "tester"
                email = "tester@example.com"
            }
            val chat = AiChat.new {
                this.user = user
                this.name = null
                this.nameSetByUser = false
                this.createdAt = Clock.System.now()
            }
            AiChatMessage.new {
                this.chat = chat
                this.sender = AiChatMessageSender.AGENT
                this.sentAt = Clock.System.now()
                this.finishedAt = null
                this.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = model.id, tokensOutput = 0)
            }.id.value
        }

        // No user message in the chat, so there is nothing to answer: the run has to return
        // before the prompt executor is touched (the base url above points nowhere).
        streamNotifier.open(messageId)
        agent.run(messageId)

        // Even on that path the stream has to end, or a client waits for an answer forever.
        assertNull(streamNotifier.of(messageId))
    }
}

package es.jvbabi.overmail.server.ai.chat

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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class FakeChatAgentTest {

    @Test
    fun `the chunks put the answer back together`() {
        val content = "<toolcall-thinking>Let me look.</toolcall-thinking>\n\n" +
            "<toolcall-search-emails subject=\"Rent\" sender=\"\"></toolcall-search-emails>\n\n" +
            "Found <email id=\"1\">it</email>."

        assertEquals(content, replayChunks(content).joinToString("") { it.text })
    }

    @Test
    fun `a tool call is one chunk, thinking and text come word by word`() {
        val chunks = replayChunks(
            "<toolcall-thinking>Let me look.</toolcall-thinking>\n\n" +
                "<toolcall-read-email emailId=\"1\" subject=\"Hi\"></toolcall-read-email>\n\nDone here"
        )

        assertEquals(
            listOf("<toolcall-read-email emailId=\"1\" subject=\"Hi\"></toolcall-read-email>"),
            chunks.filter { it.isTool }.map { it.text },
        )
        assertEquals(
            listOf("<toolcall-thinking>", "Let ", "me ", "look.", "</toolcall-thinking>", "\n\n", "\n\nDone ", "here"),
            chunks.filterNot { it.isTool }.map { it.text },
        )
    }

    @Test
    fun `the argument names the chat or asks for the newest answer`() {
        val chatId = Uuid.random()

        assertNull(FakeAiConfig.fromArguments(arrayOf("--other")))
        assertEquals(FakeAiConfig(sourceChatId = null), FakeAiConfig.fromArguments(arrayOf("--fake-ai")))
        assertEquals(FakeAiConfig(sourceChatId = chatId), FakeAiConfig.fromArguments(arrayOf("--fake-ai=$chatId")))
    }

    @Test
    fun `every question gets the recorded answer, under the recorded name`() = runTest {
        val database = OvermailDatabase(
            Database.connect("jdbc:h2:mem:fake-chat-agent;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        )
        database.init()
        val streamNotifier = AiChatStreamNotifier()
        val agent = FakeChatAgent(
            sourceChatId = null,
            database = database,
            streamNotifier = streamNotifier,
            chatNotifier = AiChatNotifier(),
        )
        val recorded = "<toolcall-thinking>Hm.</toolcall-thinking>\n\n" +
            "<toolcall-search-emails subject=\"Rent\" sender=\"\"></toolcall-search-emails>\n\nHere it is."

        val (pendingId, chatId) = database.query {
            val user = User.new {
                username = "tester"
                email = "tester@example.com"
                firstname = "Test"
                lastname = "User"
            }
            val now = Clock.System.now()
            val recordedChat = AiChat.new {
                this.user = user
                this.name = "Rent"
                this.nameSetByUser = false
                this.createdAt = now - 60.seconds
            }
            AiChatMessage.new {
                this.chat = recordedChat
                this.sender = AiChatMessageSender.AGENT
                this.sentAt = now - 60.seconds
                this.finishedAt = now - 50.seconds
                this.content = AiChatMessage.MessageContent.AgentMessageContent(text = recorded, model = "real", tokensOutput = 120)
            }
            val chat = AiChat.new {
                this.user = user
                this.name = null
                this.nameSetByUser = false
                this.createdAt = now
            }
            AiChatMessage.new {
                this.chat = chat
                this.sender = AiChatMessageSender.AGENT
                this.sentAt = now
                this.finishedAt = null
                this.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = "real", tokensOutput = 0)
            }.id.value to chat.id.value
        }

        streamNotifier.open(pendingId)
        agent.run(pendingId)

        database.query {
            val answer = assertNotNull(AiChatMessage.findById(pendingId))
            assertNotNull(answer.finishedAt)
            assertEquals(
                AiChatMessage.MessageContent.AgentMessageContent(text = recorded, model = FAKE_MODEL_ID, tokensOutput = 120),
                answer.content,
            )
            assertEquals("Rent", AiChat.findById(chatId)?.name)
        }
        assertNull(streamNotifier.of(pendingId))
    }

    @Test
    fun `retrying the recording itself writes it back as it was`() = runTest {
        val database = OvermailDatabase(
            Database.connect("jdbc:h2:mem:fake-chat-agent-retry;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        )
        database.init()
        val streamNotifier = AiChatStreamNotifier()
        val agent = FakeChatAgent(
            sourceChatId = null,
            database = database,
            streamNotifier = streamNotifier,
            chatNotifier = AiChatNotifier(),
        )
        val original = AiChatMessage.MessageContent.AgentMessageContent(text = "Here it is.", model = "real", tokensOutput = 12)

        val recordingId = database.query {
            val user = User.new {
                username = "tester"
                email = "tester@example.com"
                firstname = "Test"
                lastname = "User"
            }
            val chat = AiChat.new {
                this.user = user
                this.name = "Rent"
                this.nameSetByUser = false
                this.createdAt = Clock.System.now()
            }
            AiChatMessage.new {
                this.chat = chat
                this.sender = AiChatMessageSender.AGENT
                this.sentAt = Clock.System.now() - 10.seconds
                this.finishedAt = Clock.System.now()
                this.content = original
            }.id.value
        }

        agent.load()
        // What the retry route does to the row before the run.
        database.query {
            val message = assertNotNull(AiChatMessage.findById(recordingId))
            message.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = "real", tokensOutput = 0)
            message.finishedAt = null
        }
        streamNotifier.open(recordingId)
        agent.run(recordingId)

        database.query {
            val message = assertNotNull(AiChatMessage.findById(recordingId))
            assertEquals(original, message.content)
            assertNotNull(message.finishedAt)
        }
    }
}

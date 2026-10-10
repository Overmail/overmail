package es.jvbabi.overmail.server.ai.chat

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import es.jvbabi.overmail.server.ai.chat.tools.RenameChatTool
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Stopping an answer, with the real agent and a model that says what the test tells it to: the
 * part that matters is what a cancelled run leaves behind, and that is the agent's doing.
 */
class ChatAgentQueueTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
    )

    private val database = OvermailDatabase(
        Database.connect("jdbc:h2:mem:chat-agent-queue;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    )

    private val streamNotifier = AiChatStreamNotifier()

    @Test
    fun `stopping a running answer keeps what was written and marks it stopped`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(
            {
                emit(StreamFrame.TextDelta("Half an"))
                writing.complete(Unit)
                awaitCancellation()
            },
        )
        val queue = startQueue(llm)
        val fixture = newPendingAnswer()

        queue.enqueue(fixture.answerId)
        writing.await()
        val stream = assertNotNull(streamNotifier.of(fixture.answerId))

        queue.stop(fixture.answerId)

        // Written by the time `stop` returns, although the run was cancelled to get here.
        val (message, content) = answer(fixture.answerId)
        assertNotNull(message.finishedAt)
        assertEquals("Half an", content.text)
        assertTrue(content.stopped)

        // Whoever follows the answer is told that it ended, and how.
        assertTrue(stream.snapshot().completed)
        assertTrue(stream.snapshot().stopped)
        assertNull(streamNotifier.of(fixture.answerId))

        // A stopped answer names nothing: that would be one more model call after the stop.
        assertEquals(0, llm.namingCalls.get())
        assertNull(database.query { AiChat.findById(fixture.chatId)!!.name })
    }

    @Test
    fun `a tool call that ran before the stop is kept and not undone`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(
            {
                emit(StreamFrame.ToolCallComplete("call-1", RenameChatTool.NAME, """{"name":"Invoices"}"""))
                emit(StreamFrame.End())
            },
            {
                emit(StreamFrame.TextDelta("Renamed, and now"))
                writing.complete(Unit)
                awaitCancellation()
            },
        )
        val queue = startQueue(llm)
        val fixture = newPendingAnswer()

        queue.enqueue(fixture.answerId)
        writing.await()
        queue.stop(fixture.answerId)

        val (_, content) = answer(fixture.answerId)
        assertTrue(content.stopped)
        assertTrue(content.text.endsWith("Renamed, and now"))
        assertEquals(listOf(RenameChatTool.NAME), content.toolCalls.map { it.tool })
        assertEquals("call-1", content.toolCalls.single().id)

        // What the tool did stays done.
        assertEquals("Invoices", database.query { AiChat.findById(fixture.chatId)!!.name })
    }

    @Test
    fun `a queued answer that is stopped never runs and the queue goes on`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(
            {
                emit(StreamFrame.TextDelta("First"))
                writing.complete(Unit)
                awaitCancellation()
            },
            {
                emit(StreamFrame.TextDelta("Third"))
                emit(StreamFrame.End())
            },
        )
        val queue = startQueue(llm)
        val first = newPendingAnswer()
        val second = newPendingAnswer()
        val third = newPendingAnswer()

        queue.enqueue(first.answerId)
        writing.await()
        queue.enqueue(second.answerId)
        queue.enqueue(third.answerId)
        val secondStream = assertNotNull(streamNotifier.of(second.answerId))

        // Still behind the first one. Nobody waits for it from here on: the row is finished and
        // its stream has ended.
        queue.stop(second.answerId)
        val (secondMessage, secondContent) = answer(second.answerId)
        assertNotNull(secondMessage.finishedAt)
        assertTrue(secondContent.stopped)
        assertEquals("", secondContent.text)
        assertTrue(secondStream.snapshot().completed)
        assertTrue(secondStream.snapshot().stopped)
        assertNull(streamNotifier.of(second.answerId))

        // Stopping the first one must not take the consumer with it.
        queue.stop(first.answerId)
        awaitFinished(third.answerId)

        val (_, thirdContent) = answer(third.answerId)
        assertEquals("Third", thirdContent.text)
        assertFalse(thirdContent.stopped)

        // The model was asked for the first and the third answer, and never for the second.
        assertEquals(2, llm.streamingCalls.get())
        assertEquals("", answer(second.answerId).second.text)
    }

    @Test
    fun `an answer stopped in the queue runs when it is asked for again`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(
            {
                writing.complete(Unit)
                awaitCancellation()
            },
            {
                emit(StreamFrame.TextDelta("Second try"))
                emit(StreamFrame.End())
            },
        )
        val queue = startQueue(llm)
        val first = newPendingAnswer()
        val second = newPendingAnswer()

        queue.enqueue(first.answerId)
        writing.await()
        queue.enqueue(second.answerId)
        queue.stop(second.answerId)

        // What the retry endpoint does, while the message is still sitting in the channel.
        database.query {
            val message = AiChatMessage.findById(second.answerId)!!
            message.content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = model.id, tokensOutput = 0)
            message.finishedAt = null
        }
        queue.enqueue(second.answerId)

        queue.stop(first.answerId)
        awaitFinished(second.answerId)

        val (_, content) = answer(second.answerId)
        assertEquals("Second try", content.text)
        assertFalse(content.stopped)
    }

    @Test
    fun `stopping an answer that is finished or unknown changes nothing`() = runTest {
        val queue = startQueue(ScriptedLlmClient())
        val fixture = newPendingAnswer()
        database.query {
            val message = AiChatMessage.findById(fixture.answerId)!!
            message.content = AiChatMessage.MessageContent.AgentMessageContent(text = "All of it", model = model.id, tokensOutput = 3)
            message.finishedAt = Clock.System.now()
        }

        queue.stop(fixture.answerId)
        queue.stop(Uuid.random())

        val (_, content) = answer(fixture.answerId)
        assertEquals("All of it", content.text)
        assertFalse(content.stopped)
    }

    @Test
    fun `cancelling the consumer ends it and still finishes the running answer`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(
            {
                emit(StreamFrame.TextDelta("Cut off"))
                writing.complete(Unit)
                awaitCancellation()
            },
        )
        val queue = ChatAgentQueue(chatAgent = agent(llm), streamNotifier = streamNotifier)
        val consumer = backgroundScope.launch { queue.consume() }
        val fixture = newPendingAnswer()

        queue.enqueue(fixture.answerId)
        writing.await()

        // A shutdown, not a stop: the cancellation is the consumer's own and has to end the loop
        // instead of being taken for one run that was stopped.
        consumer.cancelAndJoin()
        assertTrue(consumer.isCancelled)

        val (message, content) = answer(fixture.answerId)
        assertNotNull(message.finishedAt)
        assertEquals("Cut off", content.text)
    }

    private fun agent(llm: LLMClient) = ChatAgent(
        config = ApplicationConfig.AiConfig(apiKey = "none", model = model.id, baseUrl = "http://localhost:1"),
        model = model,
        database = database,
        streamNotifier = streamNotifier,
        chatNotifier = AiChatNotifier(),
        mailNotifier = MailNotifier(),
        knowledgeStore = KnowledgeStore(database),
        llmClient = llm,
    )

    /** A queue with its consumer running, as `startJobs` sets it up. */
    private fun TestScope.startQueue(llm: LLMClient): ChatAgentQueue {
        val queue = ChatAgentQueue(chatAgent = agent(llm), streamNotifier = streamNotifier)
        backgroundScope.launch { queue.consume() }
        return queue
    }

    private data class Fixture(val chatId: Uuid, val answerId: Uuid)

    /** A chat of its own with a question and the still-empty answer to it. */
    private suspend fun newPendingAnswer(): Fixture {
        database.init()
        return database.query {
            val user = User.new {
                username = "owner-${Uuid.random()}"
                email = "owner-${Uuid.random()}@example.com"
                firstname = "Test"
                lastname = "User"
            }
            val chat = AiChat.new {
                this.user = user
                name = null
                nameSetByUser = false
                createdAt = Clock.System.now()
            }
            AiChatMessage.new {
                this.chat = chat
                sender = AiChatMessageSender.USER
                // Strictly before the answer: that is how the run finds the question it answers.
                sentAt = Clock.System.now() - 1.seconds
                finishedAt = Clock.System.now()
                content = AiChatMessage.MessageContent.UserMessageContent(
                    segments = listOf(AiChatMessage.MessageContent.UserMessageContent.Segment.Text("hi"))
                )
            }
            val answer = AiChatMessage.new {
                this.chat = chat
                sender = AiChatMessageSender.AGENT
                sentAt = Clock.System.now()
                finishedAt = null
                content = AiChatMessage.MessageContent.AgentMessageContent(text = "", model = model.id, tokensOutput = 0)
            }

            Fixture(chatId = chat.id.value, answerId = answer.id.value)
        }
    }

    private suspend fun answer(messageId: Uuid) = database.query {
        val message = AiChatMessage.findById(messageId)!!
        message to message.content as AiChatMessage.MessageContent.AgentMessageContent
    }

    /** Real time, not the test scheduler's: the run this waits for is on other threads. */
    private suspend fun awaitFinished(messageId: Uuid) = withContext(Dispatchers.Default) {
        while (database.query { AiChatMessage.findById(messageId)!!.finishedAt } == null) delay(10)
    }
}

/**
 * A model that answers each streaming request with the next of [turns]. Naming a chat goes through
 * [execute], which is counted and fails -- the agent treats a failed name as no name.
 */
private class ScriptedLlmClient(vararg turns: suspend FlowCollector<StreamFrame>.() -> Unit) : LLMClient() {

    private val turns = ConcurrentLinkedQueue(turns.toList())

    val streamingCalls = AtomicInteger()
    val namingCalls = AtomicInteger()

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = flow {
        streamingCalls.incrementAndGet()
        val turn = turns.poll() ?: error("The model was asked more often than the test scripted")
        turn()
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        namingCalls.incrementAndGet()
        error("No names in this test")
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("Not used")

    override fun close() = Unit
}

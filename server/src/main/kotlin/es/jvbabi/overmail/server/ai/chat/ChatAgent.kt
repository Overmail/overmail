package es.jvbabi.overmail.server.ai.chat

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.tools.ReadEmailTool
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessages
import es.jvbabi.overmail.server.database.models.User
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Beyond this the run is a loop, not an answer. Only reachable once the graph calls tools. */
private const val MAX_AGENT_ITERATIONS = 25

/**
 * Answers one chat message.
 *
 * The graph is the usual tool loop: ask the model, run whatever tools it called, hand the results
 * back, repeat until it answers in plain text.
 */
class ChatAgent(
    config: ApplicationConfig.AiConfig,
    private val model: LLModel,
    private val database: OvermailDatabase,
    private val streamNotifier: AiChatStreamNotifier,
    private val chatNotifier: AiChatNotifier,
) {

    private val promptExecutor = MultiLLMPromptExecutor(
        OpenAILLMClient(
            apiKey = config.apiKey,
            settings = OpenAIClientSettings(baseUrl = config.baseUrl),
        )
    )

    /**
     * The graph, built per run: its nodes write the answer into [stream] while the model produces
     * it, and that stream belongs to one message.
     */
    private fun strategy(stream: AiChatMessageStream) = strategy<String, String>("overmail-chat") {
        val respond by node<String, Message.Assistant>(ChatAgentGraph.RESPOND) { request ->
            llm.writeSession {
                appendPrompt { user(request) }
                streamAssistantMessage(stream)
            }
        }

        val executeTools by nodeExecuteTools(ChatAgentGraph.EXECUTE_TOOLS)

        val sendToolResults by node<ReceivedToolResults, Message.Assistant>(ChatAgentGraph.SEND_TOOL_RESULTS) { results ->
            llm.writeSession {
                appendPrompt { user { results.toolResults.forEach { result -> toolResult(result.toMessagePart()) } } }
                streamAssistantMessage(stream)
            }
        }

        edge(nodeStart forwardTo respond)

        // Tool calls before text, and not the other way round: a model that says something on its
        // way to a tool call ("let me look") produces both, and the first matching edge wins --
        // with text first, that answer would end the run before the tool ever ran.
        edge(respond forwardTo executeTools onToolCalls { true })
        edge(respond forwardTo nodeFinish onTextMessage { true })
        edge(executeTools forwardTo sendToolResults)
        edge(sendToolResults forwardTo executeTools onToolCalls { true })
        edge(sendToolResults forwardTo nodeFinish onTextMessage { true })
    }

    /**
     * Runs the agent for the pending message [messageId] and writes the answer onto it.
     *
     * The row exists before this is called (the endpoint creates it, so the client can render the
     * message as pending right away); this only fills it in.
     */
    suspend fun run(messageId: AiChatMessage.Id) {
        // Before anything can go wrong: every exit from here has to end the stream, or a client
        // waits for an answer that nobody is writing. The queue opened it when the message was
        // enqueued, and opening is idempotent.
        val stream = streamNotifier.open(messageId)

        try {
            val turn = loadTurn(messageId) ?: return

            try {
                answer(turn, stream)
            } catch (exception: Exception) {
                // Whatever the model managed to write is kept and the message is marked finished:
                // the client stops waiting for an answer that is not coming. The queue logs it.
                finish(messageId, content = stream.snapshot().content)
                throw exception
            }

            val answer = stream.snapshot().content
            finish(messageId, content = answer)
            nameChat(turn, answer)
        } finally {
            // After the row is written, so a client that reloads on `done` sees the same text it
            // was just streamed.
            stream.complete()
            streamNotifier.close(messageId)
        }
    }

    private suspend fun answer(turn: ChatTurn, stream: AiChatMessageStream) {
        val agentConfig = AIAgentConfig(
            prompt = prompt("overmail-chat") {
                system(SYSTEM_PROMPT)
                turn.history.forEach { message ->
                    when (message) {
                        is ChatTurn.Message.User -> user(message.text)
                        is ChatTurn.Message.Agent -> assistant(message.text)
                    }
                }
            },
            model = model,
            maxAgentIterations = MAX_AGENT_ITERATIONS,
        )

        val agent = GraphAIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy(stream),
            // Built per run and bound to the owner of this chat: the tools take no user argument,
            // so there is nothing the model could say to reach another user's data.
            toolRegistry = chatToolRegistry(userId = turn.userId, database = database, stream = stream),
        )

        // The return value is the last message only; what the client saw -- and what is stored --
        // is everything the run streamed, which includes text written before a tool call.
        try {
            agent.run(turn.request)
        } finally {
            agent.close()
        }
    }

    /**
     * Streams one model turn into [stream] and returns it as a message.
     *
     * Unlike `requestLLM`, a streaming request does not write its answer back into the prompt, so
     * that happens here -- without it the next turn would not see what the model just said.
     */
    private suspend fun AIAgentLLMWriteSession.streamAssistantMessage(
        stream: AiChatMessageStream,
    ): Message.Assistant {
        var firstTextOfTurn = true

        val frames = requestLLMStreaming()
            .onEach { frame ->
                if (frame !is StreamFrame.TextDelta) return@onEach
                // A turn that follows text of an earlier turn (a remark before a tool call) would
                // otherwise run into it without a break.
                if (firstTextOfTurn) {
                    firstTextOfTurn = false
                    if (stream.snapshot().content.isNotBlank()) stream.append("\n\n")
                }
                stream.append(frame.text)
            }
            .toList()

        return frames.toMessageResponse().also { response -> appendPrompt { message(response) } }
    }

    /**
     * Gives the chat a name once, from its first exchange. Skipped as soon as it has one -- and
     * never for a name the user typed, which is what `nameSetByUser` is there for.
     */
    private suspend fun nameChat(turn: ChatTurn, answer: String) {
        val needsName = database.query {
            AiChat.findById(turn.chatId)?.let { chat -> !chat.nameSetByUser && chat.name == null } == true
        }
        if (!needsName) return

        val name = try {
            promptExecutor.execute(
                prompt = prompt("overmail-chat-name") {
                    system(NAME_PROMPT)
                    user("Message:\n${turn.request}\n\nAnswer:\n$answer")
                },
                model = model,
            ).textContent()
        } catch (exception: Exception) {
            // A nameless chat is worth far less than a failed answer, so this stays a warning:
            // the answer itself is already written and must not be rolled back over a title.
            logger.warn(exception) { "Naming the chat of message failed" }
            return
        }

        val cleaned = cleanChatName(name) ?: return

        // Read back inside the transaction that writes it: the client is told about the new name
        // right after, and an entity from an earlier transaction cannot be written here.
        val chat = database.query {
            AiChat.findById(turn.chatId)
                ?.takeIf { !it.nameSetByUser && it.name == null }
                ?.also { it.name = cleaned }
        } ?: return

        chatNotifier.notifyChatUpsert(userId = turn.userId, chat = chat)
    }

    /**
     * Everything the run needs, read out of the database in one go: the entities are bound to
     * their transaction, and the model call happens long after it closed.
     */
    private suspend fun loadTurn(messageId: AiChatMessage.Id): ChatTurn? = database.query {
        val message = AiChatMessage.findById(messageId) ?: run {
            logger.warn { "Chat agent run requested for unknown message $messageId" }
            return@query null
        }

        // Everything before the pending message is context; a message written while the model is
        // answering belongs to the next run, not this one.
        val previous = AiChatMessage
            .find { (AiChatMessages.chatId eq message.chat.id) and (AiChatMessages.sentAt less message.sentAt) }
            .orderBy(AiChatMessages.sentAt to SortOrder.ASC)
            .mapNotNull { it.asTurnMessage() }

        // The last user message is what the agent is asked to answer, so it is not part of the
        // prompt history -- the graph's first node appends it.
        val request = previous.lastOrNull() as? ChatTurn.Message.User ?: run {
            logger.warn { "Chat message $messageId has no user message to answer" }
            return@query null
        }

        ChatTurn(
            chatId = message.chat.id.value,
            userId = message.chat.user.id.value,
            history = previous.dropLast(1),
            request = request.text,
        )
    }

    private suspend fun finish(messageId: AiChatMessage.Id, content: String) = database.query {
        val message = AiChatMessage.findById(messageId) ?: return@query
        // The model is written with the answer, not taken from the placeholder row: the config
        // can change between the message being created and it being answered.
        message.content = AiChatMessage.MessageContent.AgentMessageContent(text = content, model = model.id)
        message.finishedAt = Clock.System.now()
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are Overmail's assistant. You help the user with their mailbox: finding emails, " +
                "understanding what they contain, and keeping them organized. Answer in the " +
                "language the user writes in, and keep answers short.\n" +
                "The user can attach references to their message; they appear as `[email:<id>]`, " +
                "`[label:<id>]` and `[sender:<id>]`. Read an attached email with the " +
                "`${ReadEmailTool.NAME}` tool before answering questions about it, instead of " +
                "guessing from the id. Every tool only ever sees this user's own data."

        const val NAME_PROMPT =
            "Name the chat below after what the user wants, in their language. Answer with the " +
                "name alone: keep it compact. But give it a descriptive name, the user might have many chats and needs to tell them apart. Do not use the user's name or email address in the title unless it is part of the subject or the user's request. Do not use quotes around the title."

        /** The column holds 255 characters, and a title that long is not a title. */
        const val MAX_CHAT_NAME_LENGTH = 60

        /**
         * Models like to wrap a title in quotes or add a line about it, so only the first line is
         * kept. Null when nothing usable is left.
         */
        fun cleanChatName(name: String): String? = name
            .trim()
            .lineSequence()
            .firstOrNull { line -> line.isNotBlank() }
            ?.trim()
            ?.trim('"', '\'', '“', '”', '„', '`')
            ?.trim()
            ?.take(MAX_CHAT_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() }
    }
}

/**
 * The tools of one run, bound to the user whose chat it is and to the stream its answer goes into:
 * the tools take no user argument, so there is nothing the model could say to reach another user's
 * data, and what they looked at shows up in the answer.
 *
 * Top-level rather than a method, so a test can build the same registry the agent runs with.
 */
internal fun chatToolRegistry(
    userId: User.Id,
    database: OvermailDatabase,
    stream: AiChatMessageStream,
): ToolRegistry = ToolRegistry.builder()
    .tool(
        ReadEmailTool(
            userId = userId,
            database = database,
            // Its own block in the answer: the client renders the element, and markdown only
            // treats it as one when it stands alone.
            onEmailRead = { markup ->
                if (stream.snapshot().content.isNotBlank()) stream.append("\n\n")
                stream.append(markup)
            },
        )
    )
    .build()

/** Node names, so a run can be traced back to the graph without matching on property names. */
object ChatAgentGraph {
    const val RESPOND = "respond"
    const val EXECUTE_TOOLS = "executeTools"
    const val SEND_TOOL_RESULTS = "sendToolResults"
}

private data class ChatTurn(
    val chatId: AiChat.Id,
    /** Owner of the chat. Everything the tools may touch is scoped to them. */
    val userId: User.Id,
    /** The turns before the one being answered, oldest first. */
    val history: List<Message>,
    /** The text of the user message this run answers. */
    val request: String,
) {
    sealed class Message {
        abstract val text: String

        data class User(override val text: String) : Message()
        data class Agent(override val text: String) : Message()
    }
}

/**
 * The message as the model sees it, or null for one it cannot read (an unfinished answer of an
 * earlier crashed run).
 */
private fun AiChatMessage.asTurnMessage(): ChatTurn.Message? = when (val content = content) {
    is AiChatMessage.MessageContent.UserMessageContent -> ChatTurn.Message.User(content.render())
    is AiChatMessage.MessageContent.AgentMessageContent ->
        content.text.takeIf { it.isNotBlank() }?.let { ChatTurn.Message.Agent(it) }
}

/**
 * Flattens a prompt into the text the model gets. The references stay ids for now: resolving them
 * into subjects, label names and addresses is what the tools will do.
 */
private fun AiChatMessage.MessageContent.UserMessageContent.render(): String =
    segments.joinToString(" ") { segment ->
        when (segment) {
            is AiChatMessage.MessageContent.UserMessageContent.Segment.Text -> segment.content
            is AiChatMessage.MessageContent.UserMessageContent.Segment.Email -> "[email:${segment.id}]"
            is AiChatMessage.MessageContent.UserMessageContent.Segment.Label -> "[label:${segment.id}]"
            is AiChatMessage.MessageContent.UserMessageContent.Segment.Sender -> "[sender:${segment.id}]"
        }
    }.trim()

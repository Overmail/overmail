package es.jvbabi.overmail.server.ai.chat

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import es.jvbabi.overmail.server.ai.chat.tools.CreateLabelTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.WriteKnowledgeTool
import es.jvbabi.overmail.server.ai.chat.tools.LabelEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.ReadEmailTool
import es.jvbabi.overmail.server.ai.chat.tools.SearchEmailsTool
import es.jvbabi.overmail.server.ai.chat.tools.UnlabelEmailTool
import es.jvbabi.overmail.server.config.ApplicationConfig
import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.data.knowledge.KnowledgeStore
import es.jvbabi.overmail.server.data.notifier.MailNotifier
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
    /** Handed to the tools that write: what they change has to reach the screens showing it. */
    private val mailNotifier: MailNotifier,
    /** What the assistant knows about this user, shared with the classification. */
    private val knowledgeStore: KnowledgeStore,
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
    private fun strategy(
        stream: AiChatMessageStream,
        recorder: ChatToolCallRecorder,
    ) = strategy<String, String>("overmail-chat") {
        val respond by node<String, Message.Assistant>(ChatAgentGraph.RESPOND) { request ->
            llm.writeSession {
                appendPrompt { user(request) }
                streamAssistantMessage(stream)
            }
        }

        val executeTools by nodeExecuteTools(ChatAgentGraph.EXECUTE_TOOLS)

        val sendToolResults by node<ReceivedToolResults, Message.Assistant>(ChatAgentGraph.SEND_TOOL_RESULTS) { results ->
            // Kept with the message: the next turn of this chat gets the same results back
            // instead of the model calling the tool again for what it already knows.
            results.toolResults.forEach { result -> recorder.record(result) }

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

            val recorder = ChatToolCallRecorder()

            try {
                answer(turn, stream, recorder)
            } catch (exception: Exception) {
                // Whatever the model managed to write is kept and the message is marked finished:
                // the client stops waiting for an answer that is not coming. The queue logs it.
                val partial = stream.snapshot()
                finish(
                    messageId,
                    content = partial.content,
                    tokensOutput = partial.tokensOutput,
                    toolCalls = recorder.recorded(),
                )
                throw exception
            }

            val answer = stream.snapshot()
            finish(
                messageId,
                content = answer.content,
                tokensOutput = answer.tokensOutput,
                toolCalls = recorder.recorded(),
            )
            nameChat(turn, answer.content)
        } finally {
            // After the row is written, so a client that reloads on `done` sees the same text it
            // was just streamed.
            stream.complete()
            streamNotifier.close(messageId)
        }
    }

    private suspend fun answer(turn: ChatTurn, stream: AiChatMessageStream, recorder: ChatToolCallRecorder) {
        val agentConfig = AIAgentConfig(
            prompt = chatPrompt(turn),
            model = model,
            maxAgentIterations = MAX_AGENT_ITERATIONS,
        )

        val agent = GraphAIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = strategy(stream, recorder),
            // Built per run and bound to the owner of this chat: the tools take no user argument,
            // so there is nothing the model could say to reach another user's data.
            toolRegistry = chatToolRegistry(
                userId = turn.userId,
                database = database,
                mailNotifier = mailNotifier,
                knowledgeStore = knowledgeStore,
                stream = stream,
            ),
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
        var thinking = false

        /** Closes an open thinking element, so nothing after it lands inside. */
        fun endThinking() {
            if (!thinking) return
            thinking = false
            stream.append(THINKING_END)
        }

        val frames = requestLLMStreaming()
            .onEach { frame ->
                when (frame) {
                    is StreamFrame.ReasoningDelta -> {
                        val text = frame.text ?: return@onEach
                        if (!thinking) {
                            thinking = true
                            if (stream.snapshot().content.isNotBlank()) stream.append("\n\n")
                            stream.append(THINKING_START)
                        }
                        stream.append(escapeThinking(text))
                    }

                    is StreamFrame.TextDelta -> {
                        endThinking()
                        // A turn that follows text of an earlier turn (a remark before a tool
                        // call) would otherwise run into it without a break.
                        if (firstTextOfTurn) {
                            firstTextOfTurn = false
                            if (stream.snapshot().content.isNotBlank()) stream.append("\n\n")
                        }
                        stream.append(frame.text)
                    }

                    // A tool call ends the thinking that led to it; everything else is not text.
                    else -> endThinking()
                }
            }
            .toList()

        endThinking()

        return frames.toMessageResponse().also { response ->
            appendPrompt { message(response) }
            // Reported by the provider in the last frame of the stream; null when it does not
            // send usage at all, and then the answer simply stays at what the other turns cost.
            response.metaInfo.outputTokensCount?.let { tokens -> stream.addOutputTokens(tokens) }
        }
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

    private suspend fun finish(
        messageId: AiChatMessage.Id,
        content: String,
        tokensOutput: Int,
        toolCalls: List<AiChatMessage.MessageContent.AgentMessageContent.ToolCall>,
    ) = database.query {
        val message = AiChatMessage.findById(messageId) ?: return@query
        // The model is written with the answer, not taken from the placeholder row: the config
        // can change between the message being created and it being answered.
        message.content = AiChatMessage.MessageContent.AgentMessageContent(
            text = content,
            model = model.id,
            // Only what the answer cost: naming the chat is a call of its own and not part of
            // what the user asked for.
            tokensOutput = tokensOutput,
            toolCalls = toolCalls,
        )
        message.finishedAt = Clock.System.now()
    }

    internal companion object {

        /**
         * What the model reasoned before answering, shown next to the tools it used. The text
         * lives inside the element, so it streams like the rest of the answer.
         */
        const val THINKING_START = "<toolcall-thinking>"
        const val THINKING_END = "</toolcall-thinking>"

        /**
         * Reasoning is prose, and it ends up inside an element of the answer: `<` and `&` would
         * otherwise start markup, and a blank line would end the element before its closing tag.
         */
        internal fun escapeThinking(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(Regex("\\n\\s*\\n+"), "\n")

        /**
         * Grows with the tools: what the agent can do is the tool registry, and the list of what
         * it cannot do has to be kept next to it -- a model that is not told will happily promise
         * a rule it has no way to create.
         */
        const val SYSTEM_PROMPT =
            "You are Overmail's assistant. You help the user with their mailbox: finding emails " +
                "and understanding what they contain. Answer in the language the user writes in, " +
                "and keep answers short.\n" +
                "The user can attach references to their message; they appear as `[email:<id>]`, " +
                "`[label:<id>]` and `[sender:<id>]`. Read an attached email with the " +
                "`${ReadEmailTool.NAME}` tool before answering questions about it, instead of " +
                "guessing from the id. Every tool only ever sees this user's own data.\n" +
                "Use `${SearchEmailsTool.NAME}` to find emails; it answers with metadata only, so " +
                "read the ones whose content you need. Say what you searched for when nothing " +
                "was found, rather than claiming there is no such email.\n" +
                "You keep what you learn about this user: `${SearchKnowledgeTool.NAME}` looks it " +
                "up by keyword and `${ReadKnowledgeTool.NAME}` reads an entry in full. Look " +
                "before you answer anything about how this user works, who writes to them, or a " +
                "date they mentioned before -- it is cheaper than asking them again. Write with " +
                "`${WriteKnowledgeTool.NAME}` when something will still be worth knowing next " +
                "week, and give it the words you would search for; the content of one email is " +
                "not worth an entry, a decision the user made is. Do not write down what you " +
                "were only asked to do once, and never write what the user has not told you.\n" +
                "Labels are the user's own vocabulary for their mailbox, and the tools that write " +
                "them change what the user sees right away. Only use them when the user asked for " +
                "it. `${CreateLabelTool.NAME}` makes one -- and answers with the existing label " +
                "when that name is already taken, which is how to turn a name into an id. " +
                "`${LabelEmailTool.NAME}` and `${UnlabelEmailTool.NAME}` attach and detach one " +
                "email at a time. Prefer a label the user already has over a new one, and say " +
                "which labels you changed.\n" +
                "When you mention an email, a label or a person in your answer, write it as the " +
                "element `<email id=\"...\"></email>`, `<label id=\"...\"></label>` or " +
                "`<person id=\"...\"></person>` with the id the tool result or the user's " +
                "message gave you. The user never sees the element or the id: it is displayed as " +
                "the email's subject, the label's name or the person's name. Write the sentence " +
                "around it as if that text stood there -- keep the articles and cases right, and " +
                "do not repeat the subject, name or address next to the element. Use an id only " +
                "where it came from; never invent one, and write the plain name when you have " +
                "no id.\n" +
                "Your tools are everything you can do: search the mailbox and read one email. " +
                "You cannot label, archive, move, delete or send mail, you cannot change the " +
                "user's settings, and you cannot set up anything that acts on future emails. " +
                "When the user asks for something you have no tool for, say in one sentence that " +
                "you cannot do it, and stop there: never promise it, never ask what to set up, " +
                "never say it is done or under way, and do not offer to do it later."

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
 * The conversation as the model sees it: the system prompt, then every earlier turn.
 *
 * An answer that used tools is replayed as the call and its result before the text, each call
 * directly followed by its own result -- that is the shape providers expect, and it is what lets
 * a follow-up question be answered from what was already looked up.
 */
internal fun chatPrompt(turn: ChatTurn): Prompt = prompt("overmail-chat") {
    system(ChatAgent.SYSTEM_PROMPT)

    turn.history.forEach { message ->
        when (message) {
            is ChatTurn.Message.User -> user(message.text)
            is ChatTurn.Message.Agent -> {
                message.toolCalls.forEach { call ->
                    toolCall(MessagePart.Tool.Call(id = call.id, tool = call.tool, args = call.arguments))
                    toolResult(
                        MessagePart.Tool.Result(
                            id = call.id,
                            tool = call.tool,
                            output = call.result,
                            isError = call.isError,
                        )
                    )
                }
                if (message.text.isNotBlank()) assistant(message.text)
            }
        }
    }
}

/**
 * Collects what the tools of one run answered, in the order they ran.
 *
 * Synchronized: tools may run in parallel, and the list is read once the run is over.
 */
internal class ChatToolCallRecorder {
    private val calls = mutableListOf<AiChatMessage.MessageContent.AgentMessageContent.ToolCall>()

    @Synchronized
    fun record(result: ReceivedToolResult) {
        calls += AiChatMessage.MessageContent.AgentMessageContent.ToolCall(
            id = result.id,
            tool = result.tool,
            arguments = result.toolArgs.toKotlinxJsonObject().toString(),
            result = result.output,
            isError = result.resultKind !is ToolResultKind.Success,
        )
    }

    @Synchronized
    fun recorded(): List<AiChatMessage.MessageContent.AgentMessageContent.ToolCall> = calls.toList()
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
    mailNotifier: MailNotifier,
    knowledgeStore: KnowledgeStore,
    stream: AiChatMessageStream,
): ToolRegistry {
    // Its own block in the answer: the client renders the element, and markdown only treats it as
    // one when it stands alone.
    fun writeBlock(markup: String) {
        if (stream.snapshot().content.isNotBlank()) stream.append("\n\n")
        stream.append(markup)
    }

    return ToolRegistry.builder()
        .tool(ReadEmailTool(userId = userId, database = database, onEmailRead = ::writeBlock))
        .tool(SearchEmailsTool(userId = userId, database = database, onSearch = ::writeBlock))
        .tool(CreateLabelTool(userId = userId, database = database, onLabelCreated = ::writeBlock))
        .tool(
            LabelEmailTool(
                userId = userId,
                database = database,
                mailNotifier = mailNotifier,
                onLabelAttached = ::writeBlock,
            )
        )
        .tool(
            UnlabelEmailTool(
                userId = userId,
                database = database,
                mailNotifier = mailNotifier,
                onLabelDetached = ::writeBlock,
            )
        )
        .tool(SearchKnowledgeTool(userId = userId, store = knowledgeStore, onSearch = ::writeBlock))
        .tool(ReadKnowledgeTool(userId = userId, store = knowledgeStore, onRead = ::writeBlock))
        .tool(WriteKnowledgeTool(userId = userId, store = knowledgeStore, onWrite = ::writeBlock))
        .build()
}

/** Node names, so a run can be traced back to the graph without matching on property names. */
object ChatAgentGraph {
    const val RESPOND = "respond"
    const val EXECUTE_TOOLS = "executeTools"
    const val SEND_TOOL_RESULTS = "sendToolResults"
}

internal data class ChatTurn(
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

        data class Agent(
            override val text: String,
            /** What the agent looked up for this answer, so the next turn does not repeat it. */
            val toolCalls: List<AiChatMessage.MessageContent.AgentMessageContent.ToolCall> = emptyList(),
        ) : Message()
    }
}

/**
 * The message as the model sees it, or null for one it cannot read (an unfinished answer of an
 * earlier crashed run).
 */
private fun AiChatMessage.asTurnMessage(): ChatTurn.Message? = when (val content = content) {
    is AiChatMessage.MessageContent.UserMessageContent -> ChatTurn.Message.User(content.render())
    is AiChatMessage.MessageContent.AgentMessageContent ->
        // An answer that is only tool calls still belongs in the history: what it looked up is
        // the part the next turn needs.
        if (content.text.isBlank() && content.toolCalls.isEmpty()) null
        else ChatTurn.Message.Agent(text = content.text, toolCalls = content.toolCalls)
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

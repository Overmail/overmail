package es.jvbabi.overmail.server.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.database.models.AiChatMessageSender
import es.jvbabi.overmail.server.database.models.AiChatMessages
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** What a replayed answer is stored under, so a later `--fake-ai` never picks a replay as its source. */
const val FAKE_MODEL_ID = "overmail-fake"

/** Until the first piece arrives: the model reading the prompt. */
private val FIRST_CHUNK_DELAY = 900.milliseconds

/** What a tool takes to answer before the model goes on. */
private val TOOL_DELAY = 450.milliseconds

/** The bounds of the pause between two words, whatever the recorded answer took. */
private val MIN_WORD_DELAY = 4.milliseconds
private val MAX_WORD_DELAY = 40.milliseconds

/** How long a recorded answer is assumed to have taken when its timestamps do not say. */
private val FALLBACK_DURATION = 15.seconds

/**
 * Answers every question with one answer recorded earlier, streamed the way the model streamed it:
 * thinking and text word by word, a tool call as one block after a pause, the tokens growing as
 * it goes, at about the speed the original took. Started with `--fake-ai`, for working on how a
 * client shows an answer without paying for a model -- or waiting for one -- every time.
 *
 * The recording is read from the database rather than shipped with the code: it is somebody's
 * mail. [sourceChatId] names the chat whose last answer is replayed; without one it is the newest
 * real answer there is. It is read once, by [load] at startup, and stays for the whole run.
 *
 * The recording can be asked to answer again itself -- retrying it empties its row before this
 * runs, which is why it is read beforehand, and why replaying into that row writes it back as it
 * was rather than as a replay.
 */
class FakeChatAgent(
    private val sourceChatId: AiChat.Id?,
    private val database: OvermailDatabase,
    private val streamNotifier: AiChatStreamNotifier,
    private val chatNotifier: AiChatNotifier,
) : ChatResponder {

    private class Recording(
        val messageId: AiChatMessage.Id,
        val chatName: String?,
        val content: AiChatMessage.MessageContent.AgentMessageContent,
        val duration: Duration,
    )

    private val recordingLock = Mutex()
    private var recording: Recording? = null

    /** Reads the recording; before the first question, so nothing can have emptied it yet. */
    suspend fun load() {
        recording()
    }

    override suspend fun run(messageId: AiChatMessage.Id) {
        val stream = streamNotifier.open(messageId)
        var stopped = false
        try {
            val recorded = recording() ?: run {
                stream.append("There is no recorded answer to replay: `--fake-ai` needs a chat with a finished answer.")
                finish(messageId, stream.snapshot().content, tokensOutput = 0, toolCalls = emptyList())
                return
            }

            val chunks = replayChunks(recorded.content.text)
            val tools = chunks.count { it.isTool }
            val words = chunks.size - tools
            val wordDelay = ((recorded.duration - FIRST_CHUNK_DELAY - TOOL_DELAY * tools) / words.coerceAtLeast(1))
                .coerceIn(MIN_WORD_DELAY, MAX_WORD_DELAY)

            // Usage is reported per model turn, and a turn ends at a tool call: that is when the
            // count grows, by the share of the answer written since.
            val totalLength = recorded.content.text.length.coerceAtLeast(1)
            var written = 0
            var reported = 0
            fun reportTokens() {
                val due = (recorded.content.tokensOutput.toLong() * written / totalLength).toInt()
                stream.addOutputTokens(due - reported)
                reported = due
            }

            delay(FIRST_CHUNK_DELAY)
            for (chunk in chunks) {
                if (chunk.isTool) {
                    reportTokens()
                    delay(TOOL_DELAY)
                } else {
                    delay(wordDelay)
                }
                stream.append(chunk.text)
                written += chunk.text.length
            }
            written = totalLength
            reportTokens()

            if (messageId == recorded.messageId) {
                restore(recorded)
                return
            }

            val answer = stream.snapshot()
            finish(messageId, answer.content, answer.tokensOutput, recorded.content.toolCalls)
            nameChat(messageId, recorded.chatName)
        } catch (exception: CancellationException) {
            // Stopped, see [ChatAgentQueue.stop]: what was replayed so far is kept, as the real
            // agent keeps it. Non-cancellable, because the write suspends in a cancelled run.
            stopped = !currentCoroutineContext().isActive
            val partial = stream.snapshot()
            withContext(NonCancellable) {
                finish(messageId, partial.content, partial.tokensOutput, toolCalls = emptyList(), stopped = stopped)
            }
            throw exception
        } finally {
            stream.complete(stopped = stopped)
            streamNotifier.close(messageId)
        }
    }

    override suspend fun finishStopped(messageId: AiChatMessage.Id) =
        finishStoppedAnswer(messageId, database, streamNotifier)

    private suspend fun recording(): Recording? = recordingLock.withLock {
        recording ?: loadRecording()?.also {
            recording = it
            logger.info { "Fake AI replays the answer of chat \"${it.chatName}\" to every question" }
        }
    }

    private suspend fun loadRecording(): Recording? = database.query {
        val agentMessages = (AiChatMessages.sender eq AiChatMessageSender.AGENT) and AiChatMessages.finishedAt.isNotNull()
        val where = sourceChatId?.let { agentMessages and (AiChatMessages.chatId eq it) } ?: agentMessages

        AiChatMessage.find { where }
            .orderBy(AiChatMessages.sentAt to SortOrder.DESC)
            .firstNotNullOfOrNull { message ->
                val content = message.content as? AiChatMessage.MessageContent.AgentMessageContent
                    ?: return@firstNotNullOfOrNull null
                if (content.model == FAKE_MODEL_ID || content.text.isBlank()) return@firstNotNullOfOrNull null
                Recording(
                    messageId = message.id.value,
                    chatName = message.chat.name,
                    content = content,
                    duration = message.finishedAt?.let { it - message.sentAt } ?: FALLBACK_DURATION,
                )
            }
    }

    private suspend fun finish(
        messageId: AiChatMessage.Id,
        content: String,
        tokensOutput: Int,
        toolCalls: List<AiChatMessage.MessageContent.AgentMessageContent.ToolCall>,
        stopped: Boolean = false,
    ) = database.query {
        val message = AiChatMessage.findById(messageId) ?: return@query
        message.content = AiChatMessage.MessageContent.AgentMessageContent(
            text = content,
            model = FAKE_MODEL_ID,
            tokensOutput = tokensOutput,
            toolCalls = toolCalls,
            stopped = stopped,
        )
        message.finishedAt = Clock.System.now()
    }

    /** The recording written back into its own row, model and all, so it stays one. */
    private suspend fun restore(recorded: Recording) = database.query {
        val message = AiChatMessage.findById(recorded.messageId) ?: return@query
        message.content = recorded.content
        message.finishedAt = message.sentAt + recorded.duration
    }

    /** The name of the recorded chat, on a chat that has none yet -- as the real agent names it. */
    private suspend fun nameChat(messageId: AiChatMessage.Id, name: String?) {
        if (name == null) return
        val renamed = database.query {
            val chat = AiChatMessage.findById(messageId)?.chat ?: return@query null
            if (chat.nameSetByUser || chat.name != null) return@query null
            chat.name = name
            chat.user.id.value to chat
        } ?: return
        chatNotifier.notifyChatUpsert(userId = renamed.first, chat = renamed.second)
    }
}

/** A piece of a replayed answer: a word with the space after it, or a whole tool call. */
internal data class ReplayChunk(val text: String, val isTool: Boolean)

/** A tool call other than thinking: written in one go once the tool answered, see `writeBlock`. */
private val TOOL_ELEMENT = Regex("""<toolcall-(?!thinking)([a-z-]+)[^>]*>[\s\S]*?</toolcall-\1>""")
private val THINKING_TAG = Regex("""</?toolcall-thinking>""")
private val WORD = Regex("""\s*\S+\s*|\s+""")

/** [content] cut into the pieces it would have been streamed in. */
internal fun replayChunks(content: String): List<ReplayChunk> {
    val chunks = mutableListOf<ReplayChunk>()
    fun words(text: String) {
        var index = 0
        // A thinking tag is markup the server writes whole, not something the model types out.
        THINKING_TAG.findAll(text).forEach { tag ->
            WORD.findAll(text.substring(index, tag.range.first)).forEach { chunks += ReplayChunk(it.value, false) }
            chunks += ReplayChunk(tag.value, false)
            index = tag.range.last + 1
        }
        WORD.findAll(text.substring(index)).forEach { chunks += ReplayChunk(it.value, false) }
    }

    var index = 0
    TOOL_ELEMENT.findAll(content).forEach { tool ->
        words(content.substring(index, tool.range.first))
        chunks += ReplayChunk(tool.value, true)
        index = tool.range.last + 1
    }
    words(content.substring(index))
    return chunks
}

/** `--fake-ai` on its own replays the newest answer, `--fake-ai=<chat id>` the last one of that chat. */
data class FakeAiConfig(val sourceChatId: Uuid?) {
    companion object {
        private const val ARGUMENT = "--fake-ai"

        /** Null when the arguments do not ask for it. */
        fun fromArguments(args: Array<String>): FakeAiConfig? {
            val argument = args.firstOrNull { it == ARGUMENT || it.startsWith("$ARGUMENT=") } ?: return null
            val chatId = argument.substringAfter("=", missingDelimiterValue = "").takeIf { it.isNotBlank() }
            return FakeAiConfig(sourceChatId = chatId?.let(Uuid::parse))
        }
    }
}

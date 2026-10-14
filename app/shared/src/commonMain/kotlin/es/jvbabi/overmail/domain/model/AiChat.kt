package es.jvbabi.overmail.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A conversation with the assistant, as the chat list shows it. */
data class AiChat(
    val id: Uuid,
    /** Null until the chat has a name; the server writes it after the first message. */
    val name: String?,
    val createdAt: Instant,
)

sealed class AiChatMessage {
    abstract val id: Uuid
    abstract val createdAt: Instant

    /**
     * A question. Only its text is kept for now: a mail, label or person the web app put into
     * the prompt shows up by name, see [es.jvbabi.overmail.data.repository.AiChatRepositoryImpl].
     */
    data class User(
        override val id: Uuid,
        override val createdAt: Instant,
        val text: String,
    ) : AiChatMessage()

    /**
     * An answer: markdown with the agent's tool calls written into it as `<toolcall-…>` elements.
     * [isPending] while it is still being written.
     */
    data class Assistant(
        override val id: Uuid,
        override val createdAt: Instant,
        val content: String,
        val isPending: Boolean,
    ) : AiChatMessage()
}

/** What the stream of an answer brings, see `messageStream.kt` on the server. */
sealed class AiAnswerEvent {
    /** The whole answer so far; replaces what is there. */
    data class Snapshot(val content: String) : AiAnswerEvent()
    /** A piece to append. */
    data class Content(val content: String) : AiAnswerEvent()
    data object Done : AiAnswerEvent()
}

/** What sending a question started: the chat it went into, and the answer being written. */
data class AiChatSent(val chatId: Uuid, val answerMessageId: Uuid)

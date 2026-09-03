package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.AiChatMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The answers currently being written, one [AiChatMessageStream] per message.
 *
 * A stream exists only while its run does: everything before is in the database, everything after
 * as well. A reader that finds nothing here is looking at a message nobody is writing anymore --
 * either it is finished, or the run died with the process.
 */
class AiChatStreamNotifier {
    private val streams = ConcurrentHashMap<AiChatMessage.Id, AiChatMessageStream>()

    /** Opens the stream for a run that is about to start. */
    fun open(messageId: AiChatMessage.Id): AiChatMessageStream =
        streams.computeIfAbsent(messageId) { AiChatMessageStream() }

    /** The stream of a running answer, null when nothing is being written for [messageId]. */
    fun of(messageId: AiChatMessage.Id): AiChatMessageStream? = streams[messageId]

    /**
     * Drops the stream after its run finished. Readers that are still attached keep the object
     * alive until they saw [AiChatStreamEvent.Completed]; new ones fall back to the database.
     */
    fun close(messageId: AiChatMessage.Id) {
        streams.remove(messageId)
    }
}

/**
 * One answer as it is written. Holds the text produced so far, so a reader attaching in the middle
 * of a run does not start at whatever chunk happens to come next.
 */
class AiChatMessageStream {

    /**
     * DROP_OLDEST rather than a suspending emit: a reader that stopped reading must not be able to
     * stall the model run. Chunks a reader misses are not lost to it -- the gap in [Chunk.index]
     * is what tells it to take a fresh [snapshot].
     */
    private val mutableEvents = MutableSharedFlow<AiChatStreamEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<AiChatStreamEvent> = mutableEvents

    private val content = StringBuilder()
    private var nextChunk = 0
    private var completed = false

    /** The text so far, plus where in the chunk sequence it ends. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        content = content.toString(),
        nextChunk = nextChunk,
        completed = completed,
    )

    @Synchronized
    fun append(text: String) {
        if (completed || text.isEmpty()) return
        content.append(text)
        mutableEvents.tryEmit(AiChatStreamEvent.Chunk(index = nextChunk++, text = text))
    }

    @Synchronized
    fun complete() {
        if (completed) return
        completed = true
        mutableEvents.tryEmit(AiChatStreamEvent.Completed)
    }

    data class Snapshot(
        val content: String,
        /** Index the next chunk will carry; a reader ignores everything below it. */
        val nextChunk: Int,
        val completed: Boolean,
    )
}

sealed class AiChatStreamEvent {
    /**
     * Not emitted by the stream: readers inject this into their own flow to take a [snapshot] at
     * a point where nothing can slip past them, see the message stream endpoint.
     */
    data object Resynchronize : AiChatStreamEvent()

    /** A piece of the answer, to be appended to the text the reader already has. */
    data class Chunk(val index: Int, val text: String) : AiChatStreamEvent()

    /** The answer is complete and persisted. */
    data object Completed : AiChatStreamEvent()
}

package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.data.notifier.AiChatStreamEvent
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.models.AiChatMessage
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireOwnedChatMessageFromUrl
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/**
 * The answer to one message as it is written, as markdown.
 *
 * Every stream opens with a `snapshot` of the text so far, so it does not matter how late a client
 * attaches or how often it reconnects: it replaces its copy and appends from there. A message
 * nobody is writing anymore -- finished, or left behind by a restart -- is served from the
 * database and closed right away.
 */
fun Route.chatMessageStream() {
    authenticate {
        sse {
            // The api's error payload has nowhere to go once an EventSource is open, and closing
            // without `done` leaves the client reconnecting every second -- so a message it may not
            // see is reported as finished, like every other message it cannot follow.
            val message = try {
                call.requireOwnedChatMessageFromUrl()
            } catch (_: ApiException) {
                send(StreamEvent.Done)
                return@sse
            }

            // Only an agent message has text to stream; a user message has nothing to follow. Both
            // columns came with the row, so reading them needs no transaction.
            val written = message.content as? AiChatMessage.MessageContent.AgentMessageContent

            val stream = call.dependency<AiChatStreamNotifier>().of(message.id.value)
            if (stream == null) {
                send(StreamEvent.Snapshot(written?.text.orEmpty(), written?.tokensOutput ?: 0))
                send(StreamEvent.Done)
                return@sse
            }

            follow(stream)
        }
    }
}

/**
 * Sends the running answer until it is complete.
 *
 * Chunks are numbered, and the snapshot says where in that sequence it ends: a chunk below it was
 * already in the snapshot, a gap above it means the reader fell behind and takes a fresh snapshot
 * instead of appending text with a hole in it.
 */
private suspend fun ServerSSESession.follow(stream: AiChatMessageStream) {
    var nextChunk = 0

    stream.events
        // Runs once the collector is registered, so nothing emitted after this snapshot can be
        // missed -- what it can do is arrive twice, which the chunk index sorts out.
        .onSubscription { emit(AiChatStreamEvent.Resynchronize) }
        .transformWhile { event ->
            when (event) {
                is AiChatStreamEvent.Resynchronize -> {
                    val snapshot = stream.snapshot()
                    emit(StreamEvent.Snapshot(snapshot.content, snapshot.tokensOutput))
                    nextChunk = snapshot.nextChunk
                    if (snapshot.completed) emit(StreamEvent.Done)
                    !snapshot.completed
                }

                is AiChatStreamEvent.Chunk -> {
                    when {
                        event.index < nextChunk -> Unit
                        event.index > nextChunk -> {
                            val snapshot = stream.snapshot()
                            emit(StreamEvent.Snapshot(snapshot.content, snapshot.tokensOutput))
                            nextChunk = snapshot.nextChunk
                        }

                        else -> {
                            emit(StreamEvent.Content(event.text))
                            nextChunk = event.index + 1
                        }
                    }
                    true
                }

                is AiChatStreamEvent.Usage -> {
                    emit(StreamEvent.Usage(event.tokensOutput))
                    true
                }

                AiChatStreamEvent.Completed -> {
                    // The whole text once more: a chunk dropped while this client was slow would
                    // otherwise stay missing in what it renders.
                    val snapshot = stream.snapshot()
                    emit(StreamEvent.Snapshot(snapshot.content, snapshot.tokensOutput))
                    emit(StreamEvent.Done)
                    false
                }
            }
        }
        .collect { event -> send(event) }
}

private suspend fun ServerSSESession.send(event: StreamEvent) =
    send(ServerSentEvent(data = json.encodeToString<StreamEvent>(event)))

@Serializable
private sealed class StreamEvent {

    /** The whole answer so far, replacing what the client has. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        @SerialName("content") val content: String,
        @SerialName("tokens_output") val tokensOutput: Int,
    ) : StreamEvent()

    /** What the answer has cost so far, as a running total. */
    @Serializable
    @SerialName("usage")
    data class Usage(@SerialName("tokens_output") val tokensOutput: Int) : StreamEvent()

    /** A piece of the answer, to be appended to what the client already has. */
    @Serializable
    @SerialName("content")
    data class Content(@SerialName("content") val content: String) : StreamEvent()

    /** The answer is complete; the client closes the stream on this. */
    @Serializable
    @SerialName("done")
    data object Done : StreamEvent()
}

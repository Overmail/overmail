package es.jvbabi.overmail.server.http.webapp.ai.chat

import es.jvbabi.overmail.server.data.notifier.AiChatMessageStream
import es.jvbabi.overmail.server.data.notifier.AiChatStreamEvent
import es.jvbabi.overmail.server.data.notifier.AiChatStreamNotifier
import es.jvbabi.overmail.server.database.models.AiChatMessage
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

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
            val message = resolveMessage()
            if (message == null) {
                // Closing without `done` would leave the client reconnecting every second, so an
                // unknown message is reported as finished like any other.
                send(StreamEvent.Done)
                return@sse
            }

            val stream = call.application.dependencies
                .resolve<AiChatStreamNotifier>()
                .of(message.id)

            if (stream == null) {
                send(StreamEvent.Snapshot(message.content))
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
                    emit(StreamEvent.Snapshot(snapshot.content))
                    nextChunk = snapshot.nextChunk
                    if (snapshot.completed) emit(StreamEvent.Done)
                    !snapshot.completed
                }

                is AiChatStreamEvent.Chunk -> {
                    when {
                        event.index < nextChunk -> Unit
                        event.index > nextChunk -> {
                            val snapshot = stream.snapshot()
                            emit(StreamEvent.Snapshot(snapshot.content))
                            nextChunk = snapshot.nextChunk
                        }

                        else -> {
                            emit(StreamEvent.Content(event.text))
                            nextChunk = event.index + 1
                        }
                    }
                    true
                }

                AiChatStreamEvent.Completed -> {
                    // The whole text once more: a chunk dropped while this client was slow would
                    // otherwise stay missing in what it renders.
                    emit(StreamEvent.Snapshot(stream.snapshot().content))
                    emit(StreamEvent.Done)
                    false
                }
            }
        }
        .collect { event -> send(event) }
}

/**
 * The message the path points at, or null when the signed-in user may not see it. Checked
 * through the chat: the message id alone says nothing about who owns it.
 */
private suspend fun ServerSSESession.resolveMessage(): StreamedMessage? {
    val chat = call.resolveChatWithOwnerCheck() ?: return null
    val messageId = call.parameters["messageId"]?.let(Uuid::parseOrNull) ?: return null

    return call.overmailDatabase().query {
        val message = AiChatMessage.findById(messageId)?.takeIf { it.chat.id == chat.id } ?: return@query null
        StreamedMessage(
            id = message.id.value,
            // Only an agent message has text to stream; a user message has nothing to follow.
            content = (message.content as? AiChatMessage.MessageContent.AgentMessageContent)?.text.orEmpty(),
        )
    }
}

private data class StreamedMessage(val id: AiChatMessage.Id, val content: String)

private suspend fun ServerSSESession.send(event: StreamEvent) =
    send(ServerSentEvent(data = json.encodeToString<StreamEvent>(event)))

@Serializable
private sealed class StreamEvent {

    /** The whole answer so far, replacing what the client has. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(@SerialName("content") val content: String) : StreamEvent()

    /** A piece of the answer, to be appended to what the client already has. */
    @Serializable
    @SerialName("content")
    data class Content(@SerialName("content") val content: String) : StreamEvent()

    /** The answer is complete; the client closes the stream on this. */
    @Serializable
    @SerialName("done")
    data object Done : StreamEvent()
}

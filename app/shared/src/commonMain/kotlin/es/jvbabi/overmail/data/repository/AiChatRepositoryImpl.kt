package es.jvbabi.overmail.data.repository

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.data.network.followServerSentEvents
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.AiAnswerEvent
import es.jvbabi.overmail.domain.model.AiChat
import es.jvbabi.overmail.domain.model.AiChatMessage
import es.jvbabi.overmail.domain.model.AiChatSent
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AiChatRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = Logger.withTag("AiChatRepository")

/** How long a chat socket that broke waits before it is opened again. */
private val RECONNECT_DELAY = 5.seconds

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

/**
 * The assistant, over the same routes the web app's sidebar uses (`http/webapp/ai/` on the server):
 * the chat list over its socket, a chat's messages and a new question over http, and the answer
 * over its server-sent events.
 */
class AiChatRepositoryImpl(
    private val httpClient: HttpClient,
) : AiChatRepository {

    override fun getChats(user: OvermailAccount): Flow<List<AiChat>> = channelFlow {
        // Only the first page: older chats are not offered by the app yet.
        val chats = mutableMapOf<Uuid, AiChat>()
        fun publish() = trySend(chats.values.sortedByDescending { it.createdAt })

        val url = URLBuilder(user.homeserver).apply {
            protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
            appendPathSegments("api", "webapp", "ai", "socket")
        }.build()

        while (true) {
            val result = safeRequest {
                httpClient.webSocket(
                    urlString = url.toString(),
                    request = { bearerAuth(user.token) },
                ) {
                    // A reconnect gets the first page anew; what was deleted meanwhile is not in it.
                    chats.clear()
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        when (val message = json.decodeFromString<ApiAiSocketMessage>(text)) {
                            is ApiAiSocketMessage.Chats -> message.chats.forEach { chats[it.id] = it.toModel() }
                            is ApiAiSocketMessage.ChatUpsert -> chats[message.chat.id] = message.chat.toModel()
                            is ApiAiSocketMessage.ChatDelete -> chats.remove(message.chatId)
                        }
                        publish()
                    }
                }
            }
            logger.w(result.exceptionOrNull()) { "The chat socket closed, reconnecting in $RECONNECT_DELAY" }
            delay(RECONNECT_DELAY)
        }
    }

    override suspend fun getMessages(chatId: Uuid, user: OvermailAccount): Result<List<AiChatMessage>> = safeRequest {
        val response = httpClient.get(URLBuilder(user.homeserver).apply {
            appendPathSegments("api", "webapp", "ai", "chat", chatId.toString(), "history")
        }.build()) {
            bearerAuth(user.token)
        }
        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()

        json.decodeFromString<ApiChatHistoryResponse>(response.body<String>()).messages.map { it.toModel() }
    }

    override suspend fun send(chatId: Uuid?, text: String, user: OvermailAccount): Result<AiChatSent> = safeRequest {
        val response = httpClient.post(URLBuilder(user.homeserver).apply {
            appendPathSegments("api", "webapp", "ai", "chat")
        }.build()) {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody(
                ApiChatMessageRequest(
                    chatId = chatId,
                    prompt = ApiChatMessageRequest.Prompt(
                        mode = "normal",
                        segments = listOf(ApiChatMessageRequest.Segment(type = "text", content = text)),
                    ),
                )
            )
        }
        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()

        val body = response.body<ApiMessageResponse>()
        AiChatSent(chatId = body.chatId, answerMessageId = body.answerMessageId)
    }

    override suspend fun retry(chatId: Uuid, messageId: Uuid, user: OvermailAccount): Result<Unit> = safeRequest {
        val response = httpClient.post(URLBuilder(user.homeserver).apply {
            appendPathSegments("api", "webapp", "ai", "chat", chatId.toString(), "message", messageId.toString(), "retry")
        }.build()) {
            bearerAuth(user.token)
        }
        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()
    }

    override fun followAnswer(chatId: Uuid, messageId: Uuid, user: OvermailAccount): Flow<AiAnswerEvent> = channelFlow {
        val url = URLBuilder(user.homeserver).apply {
            appendPathSegments("api", "webapp", "ai", "chat", chatId.toString(), "message", messageId.toString(), "stream")
        }.build()

        httpClient.followServerSentEvents(Url(url.toString()), user.token, "answer $messageId") { data ->
            when (val event = json.decodeFromString<ApiStreamEvent>(data)) {
                is ApiStreamEvent.Snapshot -> send(AiAnswerEvent.Snapshot(event.content))
                is ApiStreamEvent.Content -> send(AiAnswerEvent.Content(event.content))
                is ApiStreamEvent.Usage -> Unit
                ApiStreamEvent.Done -> send(AiAnswerEvent.Done)
            }
        }
    }
        // The stream reconnects on its own once the server closes it; done is where it ends.
        .transformWhile { event ->
            emit(event)
            event !is AiAnswerEvent.Done
        }
}

/** `/api/webapp/ai/socket`, `http/webapp/ai/aiSocket.kt`. */
@Serializable
private sealed class ApiAiSocketMessage {
    @Serializable
    @SerialName("data.chats")
    data class Chats(@SerialName("chats") val chats: List<ApiAiChat>) : ApiAiSocketMessage()

    @Serializable
    @SerialName("update.chat.upsert")
    data class ChatUpsert(@SerialName("chat") val chat: ApiAiChat) : ApiAiSocketMessage()

    @Serializable
    @SerialName("update.chat.delete")
    data class ChatDelete(@SerialName("chat_id") val chatId: Uuid) : ApiAiSocketMessage()
}

@Serializable
private data class ApiAiChat(
    @SerialName("id") val id: Uuid,
    @SerialName("name") val name: String?,
    @SerialName("created_at") val createdAt: Long,
) {
    fun toModel() = AiChat(id = id, name = name, createdAt = Instant.fromEpochSeconds(createdAt))
}

/** `GET /api/webapp/ai/chat/{chatId}/history`, `http/webapp/ai/chat/history.kt`. */
@Serializable
private data class ApiChatHistoryResponse(@SerialName("messages") val messages: List<ApiChatHistoryMessage>)

@Serializable
private sealed class ApiChatHistoryMessage {
    abstract fun toModel(): AiChatMessage

    @Serializable
    @SerialName("user")
    data class User(
        @SerialName("id") val id: Uuid,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("content") val content: List<ApiChatHistorySegment>,
    ) : ApiChatHistoryMessage() {
        override fun toModel() = AiChatMessage.User(
            id = id,
            createdAt = Instant.fromEpochSeconds(createdAt),
            text = content.joinToString("") { it.text },
        )
    }

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        @SerialName("id") val id: Uuid,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("pending") val pending: Boolean,
        @SerialName("content") val content: String,
    ) : ApiChatHistoryMessage() {
        override fun toModel() = AiChatMessage.Assistant(
            id = id,
            createdAt = Instant.fromEpochSeconds(createdAt),
            content = content,
            isPending = pending,
        )
    }
}

/** A reference that no longer resolves comes without its name, and is left as nothing. */
@Serializable
private sealed class ApiChatHistorySegment {
    abstract val text: String

    @Serializable
    @SerialName("text")
    data class Text(@SerialName("content") val content: String) : ApiChatHistorySegment() {
        override val text get() = content
    }

    @Serializable
    @SerialName("email")
    data class Email(@SerialName("subject") val subject: String?) : ApiChatHistorySegment() {
        override val text get() = subject?.let { "\"$it\"" }.orEmpty()
    }

    @Serializable
    @SerialName("label")
    data class Label(@SerialName("name") val name: String?) : ApiChatHistorySegment() {
        override val text get() = name?.let { "#$it" }.orEmpty()
    }

    @Serializable
    @SerialName("sender")
    data class Sender(
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String?,
    ) : ApiChatHistorySegment() {
        override val text get() = (name ?: address)?.let { "@$it" }.orEmpty()
    }
}

/** `POST /api/webapp/ai/chat`, `http/webapp/ai/chat/message.kt`. */
@Serializable
private data class ApiChatMessageRequest(
    @SerialName("chat_id") val chatId: Uuid?,
    @SerialName("prompt") val prompt: Prompt,
) {
    @Serializable
    data class Prompt(
        @SerialName("type") val mode: String,
        @SerialName("segments") val segments: List<Segment>,
    )

    @Serializable
    data class Segment(
        @SerialName("type") val type: String,
        @SerialName("content") val content: String,
    )
}

@Serializable
private data class ApiMessageResponse(
    @SerialName("chat_id") val chatId: Uuid,
    @SerialName("answer_message_id") val answerMessageId: Uuid,
)

/** The events of `messageStream.kt`. */
@Serializable
private sealed class ApiStreamEvent {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(@SerialName("content") val content: String) : ApiStreamEvent()

    @Serializable
    @SerialName("content")
    data class Content(@SerialName("content") val content: String) : ApiStreamEvent()

    @Serializable
    @SerialName("usage")
    data object Usage : ApiStreamEvent()

    @Serializable
    @SerialName("done")
    data object Done : ApiStreamEvent()
}

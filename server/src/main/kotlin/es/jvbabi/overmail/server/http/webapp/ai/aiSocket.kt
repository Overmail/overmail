package es.jvbabi.overmail.server.http.webapp.ai

import es.jvbabi.overmail.server.data.notifier.AiChatEvent
import es.jvbabi.overmail.server.data.notifier.AiChatNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.AiChats
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUser
import io.ktor.server.auth.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Chats per page, for the first one and for every `request.chats.more` after it. */
private const val CHAT_PAGE_SIZE = 30

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun Route.aiSocket() {
    authenticate {
        webSocket {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val chatNotifier = application.dependencies.resolve<AiChatNotifier>()

            val user = call.requireAuthenticatedUser()

            /**
             * Everything created after this is above the window the client holds, no matter how
             * far it has paged: it did not exist when the first page was cut. Lets a chat opened
             * in another tab through without the socket having loaded it.
             */
            val openedAt = Clock.System.now()

            /**
             * Lower edge of the window: the creation time of the oldest chat sent so far, null
             * until the first page went out. `request.chats.more` continues below it.
             */
            var oldestSentChat: Instant? = null

            /**
             * The chats this client holds -- what an update may be sent for, and the page dedup.
             * Concurrent: the subscription below runs in its own coroutine and writes to it while
             * a page is being cut.
             */
            val sentChats = ConcurrentHashMap.newKeySet<AiChat.Id>()

            suspend fun sendNextChats() {
                // Read inside the transaction and kept at full precision: the payload carries
                // whole seconds, and cutting the cursor down to those would skip every chat
                // created between the truncated second and the real edge.
                var oldestInPage: Instant? = null

                /**
                 * Creation time of the user's oldest chat overall, not just of this page. The
                 * client compares its own oldest against it to know whether paging further down
                 * can still turn anything up -- an empty page would tell it the same, but only
                 * after a pointless round trip.
                 */
                var oldestOverall: Instant? = null

                val page = database.query {
                    val query = AiChats
                        .selectAll()
                        .where { AiChats.userId eq user.id }

                    // lessEq, not less: two chats sharing a creation timestamp would otherwise
                    // fall between two pages and never be sent. The overlap this produces is
                    // already-sent rows, dropped below.
                    oldestSentChat?.let { bound -> query.andWhere { AiChats.createdAt lessEq bound } }

                    val rows = query
                        .orderBy(AiChats.createdAt, SortOrder.DESC)
                        .limit(CHAT_PAGE_SIZE)
                        .toList()

                    oldestInPage = rows.minOfOrNull { row -> row[AiChats.createdAt] }

                    val oldestOverallColumn = AiChats.createdAt.min()
                    oldestOverall = AiChats
                        .select(oldestOverallColumn)
                        .where { AiChats.userId eq user.id }
                        .firstOrNull()
                        ?.get(oldestOverallColumn)

                    rows.map { row ->
                        AiChatPayload(
                            id = row[AiChats.id].value,
                            name = row[AiChats.name],
                            nameSetByUser = row[AiChats.nameSetByUser],
                            createdAt = row[AiChats.createdAt].epochSeconds,
                        )
                    }
                }

                val newChats = page.filterNot { chat -> chat.id in sentChats }
                sentChats.addAll(newChats.map { chat -> chat.id })
                oldestInPage?.let { oldest -> oldestSentChat = oldest }

                // Sent even when empty: that is how the client sees it reached the end.
                sendSerialized<AiServerMessage>(
                    AiServerMessage.Chats(
                        chats = newChats,
                        oldestCreatedAt = oldestOverall?.epochSeconds,
                    )
                )
            }

            sendNextChats()

            // One subscription for the whole socket, not one per chat: the notifier is keyed by
            // user, and a chat that does not exist yet has no id to subscribe to. Started after
            // the first page, so the client has the list before the first update lands.
            launch {
                chatNotifier.subscribe(user.id.value).collect { event ->
                    when (event) {
                        is AiChatEvent.Upsert -> {
                            // Columns only, never a reference: the entity arrives here detached
                            // from the transaction that loaded it.
                            val chat = event.chat
                            val chatId = chat.id.value
                            // A chat below the window would reach the client as an unknown one --
                            // it has not paged that far down.
                            if (chatId !in sentChats && chat.createdAt <= openedAt) return@collect

                            sentChats.add(chatId)
                            sendSerialized<AiServerMessage>(
                                AiServerMessage.ChatUpsert(
                                    AiChatPayload(
                                        id = chatId,
                                        name = chat.name,
                                        nameSetByUser = chat.nameSetByUser,
                                        createdAt = chat.createdAt.epochSeconds,
                                    )
                                )
                            )
                        }

                        is AiChatEvent.Delete -> {
                            if (!sentChats.remove(event.chatId)) return@collect
                            sendSerialized<AiServerMessage>(AiServerMessage.ChatDelete(event.chatId))
                        }
                    }
                }
            }

            for (frame in incoming) {
                val message = frame as? Frame.Text ?: continue
                when (json.decodeFromString<AiClientMessage>(message.readText())) {
                    is AiClientMessage.LoadMoreChats -> sendNextChats()
                }
            }
        }
    }
}

@Serializable
private sealed class AiServerMessage {
    /** A page of chats, newest first. The first one on connect, one per `request.chats.more`. */
    @Serializable
    @SerialName("data.chats")
    data class Chats(
        @SerialName("chats") val chats: List<AiChatPayload>,
        /**
         * Creation time of the oldest chat the user has, null when there are none. Whole seconds
         * like [AiChatPayload.createdAt], so the client can compare the two directly.
         */
        @SerialName("oldest_created_at") val oldestCreatedAt: Long?,
    ) : AiServerMessage()

    @Serializable
    @SerialName("update.chat.upsert")
    data class ChatUpsert(@SerialName("chat") val chat: AiChatPayload) : AiServerMessage()

    @Serializable
    @SerialName("update.chat.delete")
    data class ChatDelete(@SerialName("chat_id") val chatId: Uuid) : AiServerMessage()
}

@Serializable
private sealed class AiClientMessage {
    /** The next page below the oldest chat this socket has sent. */
    @Serializable
    @SerialName("request.chats.more")
    object LoadMoreChats : AiClientMessage()
}

@Serializable
private data class AiChatPayload(
    @SerialName("id") val id: Uuid,
    /** Null until the chat has a name; it is written after the first message. */
    @SerialName("name") val name: String?,
    @SerialName("name_set_by_user") val nameSetByUser: Boolean,
    @SerialName("created_at") val createdAt: Long,
)

package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.AiChat
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Changes to a user's chat list. A chat can appear or be renamed without the client having asked
 * for it -- the name is written by the model after the first message, and a second tab creates
 * chats this one never saw.
 *
 * Keyed by user, not by chat: the subscriber is a sidebar showing all of them, and a chat that
 * does not exist yet has no id to subscribe to.
 */
class AiChatNotifier {
    private val channels = ConcurrentHashMap<User.Id, MutableSharedFlow<AiChatEvent>>()

    fun subscribe(userId: User.Id): SharedFlow<AiChatEvent> {
        // The buffer is what makes tryEmit work: a MutableSharedFlow() without buffer rejects
        // every tryEmit as soon as someone collects, silently dropping all events. No replay --
        // subscribers get the current chats with the initial payload; only live changes flow
        // through here.
        return channels.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    fun notifyChatUpsert(userId: User.Id, chat: AiChat) {
        channels[userId]?.tryEmit(AiChatEvent.Upsert(chat))
    }

    fun notifyChatDelete(userId: User.Id, chatId: AiChat.Id) {
        channels[userId]?.tryEmit(AiChatEvent.Delete(chatId))
    }
}

sealed class AiChatEvent {
    data class Upsert(val chat: AiChat) : AiChatEvent()

    // The id, not the entity: the row is gone by the time a subscriber reads this, and reading a
    // deleted entity's columns hits the database again.
    data class Delete(val chatId: AiChat.Id) : AiChatEvent()
}

package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Says that a user's mailbox moved: a mail was imported, or one was archived, unarchived or filed
 * as spam.
 *
 * The event carries no number and no mail id. What "in the mailbox" means is a query over an
 * event log (see `emailIsNotArchived`), so a subscriber that wants a count re-reads it -- adding
 * up deltas here would mean keeping that logic in a second place and drifting from it.
 *
 * Keyed by user: every socket of that user hears it, nobody else does.
 */
class MailboxNotifier {
    private val channels = ConcurrentHashMap<User.Id, MutableSharedFlow<MailboxEvent>>()

    fun subscribe(userId: User.Id): SharedFlow<MailboxEvent> {
        // Buffered, or tryEmit would be rejected as soon as somebody collects. No replay: a
        // subscriber reads the current state itself before it starts listening.
        return channels.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    /**
     * Announce after the writing transaction committed, never inside it -- a subscriber reacts by
     * reading, and an open transaction would hand it the state from before the write.
     */
    fun notifyMailboxChanged(userId: User.Id) {
        channels[userId]?.tryEmit(MailboxEvent.Changed)
    }
}

sealed class MailboxEvent {
    /** Something moved; ask again for what you show. */
    object Changed : MailboxEvent()
}

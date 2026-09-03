package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.User
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Says that something about a user's mail changed: one was imported, archived, unarchived, filed
 * as spam, read, labelled.
 *
 * The one place to announce a mail change, so there is one thing to remember at a write site
 * rather than one per reader. Every write that a screen could be showing goes through here --
 * announce it after the transaction committed, or a reader reacting to it reads the state from
 * before the write.
 *
 * The event carries the mail it happened to and nothing else. What a reader shows is a query (a
 * count over an event log, a row's labels), so it re-reads what it needs; a delta here would mean
 * keeping that logic in a second place and drifting from it.
 *
 * Keyed by user, not by mail: one channel per socket instead of one per row on screen, so a
 * listing of a thousand mails is one subscription and there is nothing per-mail to clean up.
 */
class MailNotifier {
    private val channels = ConcurrentHashMap<User.Id, MutableSharedFlow<MailEvent>>()

    fun subscribe(userId: User.Id): SharedFlow<MailEvent> {
        // Buffered, or tryEmit would be rejected as soon as somebody collects. No replay: a
        // subscriber reads the current state itself before it starts listening.
        return channels.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    fun notifyMailChanged(userId: User.Id, emailId: Email.Id) {
        channels[userId]?.tryEmit(MailEvent.Changed(emailId))
    }
}

sealed class MailEvent {
    /** [emailId] moved somehow; ask again for what you show of it. */
    data class Changed(val emailId: Email.Id) : MailEvent()
}

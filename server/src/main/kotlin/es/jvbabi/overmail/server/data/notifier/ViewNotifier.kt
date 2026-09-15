package es.jvbabi.overmail.server.data.notifier

import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.database.models.View
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Says that something about a user's views changed: one was created, renamed, reconfigured, moved
 * or deleted.
 *
 * The one place to announce a view change, like [MailNotifier] -- announce it after the
 * transaction committed, or a reader reacting to it reads the state from before the write.
 *
 * The event carries the view it happened to and nothing else. What a reader shows is a query --
 * the list in the sidebar, the groupings of the one view it is listing mail for -- so it re-reads
 * what it needs; a delta here would mean keeping that logic in a second place and drifting from
 * it, and it is a handful of rows either way.
 *
 * Keyed by user, not by view: the subscriber is a sidebar showing all of them, and a view that
 * does not exist yet has no id to subscribe to. A reader that shows one view filters the stream
 * by [ViewEvent.Changed.viewId].
 */
class ViewNotifier {
    private val channels = ConcurrentHashMap<User.Id, MutableSharedFlow<ViewEvent>>()

    fun subscribe(userId: User.Id): SharedFlow<ViewEvent> {
        // Buffered, or tryEmit would be rejected as soon as somebody collects. No replay: a
        // subscriber reads the current views itself before it starts listening.
        return channels.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
    }

    /**
     * [viewId] is not there any more when this announces a deletion -- the id is what says which
     * reader is concerned, not something to load. Everybody re-reads either way.
     */
    fun notifyViewChanged(userId: User.Id, viewId: View.Id) {
        channels[userId]?.tryEmit(ViewEvent.Changed(viewId))
    }
}

sealed class ViewEvent {
    /** [viewId] was created, renamed, reconfigured, moved or deleted; ask again for what you show. */
    data class Changed(val viewId: View.Id) : ViewEvent()
}

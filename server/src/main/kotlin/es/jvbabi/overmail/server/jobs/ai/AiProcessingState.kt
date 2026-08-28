package es.jvbabi.overmail.server.jobs.ai

import es.jvbabi.overmail.server.domain.models.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/** The mail the agent has open, and whose it is -- a socket may only tell its own user. */
data class AiProcessing(
    val emailId: Uuid,
    val userId: Uuid,
)

/**
 * Which mail the agent is on right now.
 *
 * Deliberately not in the database and therefore not a repository: it is true for as long as one run
 * takes and means nothing after a restart, so a row for it would be wrong more often than right --
 * and every write would wake every flow watching the queue.
 *
 * A `StateFlow` rather than a signal, so a screen that connects halfway through a run is told about
 * the mail already in progress instead of waiting for the next one. One mail at a time, which is why
 * this holds one and not a set: the model is the bottleneck, and two runs at once would only make
 * both slower while making the tag vocabulary they are reconciling against a moving target.
 */
class AiProcessingState {

    private val state = MutableStateFlow<AiProcessing?>(null)

    val current: StateFlow<AiProcessing?> = state.asStateFlow()

    /** Moves the mark onto [email]. */
    fun announce(email: Email) {
        state.value = AiProcessing(emailId = email.id, userId = email.imapAccount.user.id)
    }

    /**
     * Takes the mark off: the agent is between mails, or has stopped.
     *
     * Called after every mail rather than only at the end, unlike the version this is ported from.
     * That one left the mark on the last mail it read, which reads as "where the agent is" -- and
     * that was right for a walk that never stopped. This queue does stop, all the time: it is empty
     * most of the day, and a mark left on the last mail of an empty queue is a spinner that never
     * goes out.
     */
    fun clear() {
        state.value = null
    }
}

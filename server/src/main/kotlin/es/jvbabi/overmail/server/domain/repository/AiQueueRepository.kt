package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** How many times a mail may fail before the queue leaves it alone. */
const val MAX_QUEUE_ATTEMPTS = 3

interface AiQueueRepository {
    /**
     * Puts the mail in the queue. False where it is already in it, which is not an error -- asking
     * twice for the same mail is one run.
     */
    suspend fun enqueue(emailId: Uuid, reason: ClassificationReason): Boolean

    /**
     * The next mail to read: the one that has been waiting longest and has not failed too often.
     *
     * Oldest first, so a mail that arrived while a bulk request was being worked through does not
     * wait for the whole of it. Null means there is nothing owed.
     */
    suspend fun next(): AiQueueEntry?

    /** Takes the mail out of the queue, which is what having been read means. */
    suspend fun done(entryId: Uuid)

    /**
     * Counts the attempt and keeps the reason. The row stays either way: past
     * [MAX_QUEUE_ATTEMPTS] it is simply no longer handed out, so a mail that breaks something can be
     * looked at instead of vanishing.
     */
    suspend fun failed(entryId: Uuid, why: String)

    /** How many of [user]'s mails are still owed a run. Mails past their attempts do not count. */
    suspend fun pendingFor(user: User): Int

    /**
     * Fires whenever the queue changes, so a worker can wake up and a screen can be told.
     *
     * A signal and not the work list: what is owed is asked for with [next] afterwards, because
     * between the notice and the query the queue has usually moved on.
     */
    fun changes(): Flow<Unit>
}

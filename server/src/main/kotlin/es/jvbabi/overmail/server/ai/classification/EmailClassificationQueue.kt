package es.jvbabi.overmail.server.ai.classification

import es.jvbabi.overmail.server.database.models.Email
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Queue of emails waiting to be classified by the AI.
 *
 * [enqueue] is safe to call from any thread or coroutine; [consume] is meant to be run by a single
 * consumer coroutine (see `startJobs` in `AppModule`).
 */
class EmailClassificationQueue {

    /** IDs currently waiting in [channel]. Used to skip emails that are already queued. */
    private val pending = ConcurrentHashMap.newKeySet<Email.Id>()

    private val channel = Channel<Email.Id>(capacity = Channel.UNLIMITED)

    /**
     * Enqueues an email ID for classification if it is not already in the queue.
     * @param emailId The ID of the email to enqueue.
     */
    fun enqueue(emailId: Email.Id) {
        if (!pending.add(emailId)) return // already waiting to be classified
        if (channel.trySend(emailId).isFailure) pending.remove(emailId)
    }

    /**
     * Consumes queued emails until the queue is closed. Suspends while the queue is empty.
     */
    suspend fun consume() {
        for (emailId in channel) {
            // Released before processing, so an email changing mid-classification can be re-queued.
            pending.remove(emailId)
            println(emailId)
        }
    }
}

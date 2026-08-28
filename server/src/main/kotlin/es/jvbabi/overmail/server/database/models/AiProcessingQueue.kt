package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.domain.models.ClassificationReason
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * The mails waiting to be read by the agent.
 *
 * A queue and not a flag, which is the whole difference from the version this replaces. That one was
 * `ai_processed` on the mail plus "everything not yet processed" as a query, and the trouble with it
 * was not the shape but what it implied: importing a mailbox of ten thousand mails put ten thousand
 * classifications in front of the agent, and the first thing anybody wanted was a way to stop it.
 * Here nothing is in the queue that was not put there -- a mail arriving now, or a stretch of the
 * mailbox somebody asked for -- and a mail nobody ever asks about is simply never read.
 *
 * In the database rather than in memory. A queue that empties on restart loses exactly the mails a
 * restart interrupted, and with no backfill behind it there is nothing that would ever pick them up
 * again.
 *
 * A row is deleted when its mail has been read. What the run came to is kept elsewhere, see
 * [EmailAiClassifications] -- this table is about what is still owed.
 */
object AiProcessingQueue : UuidTable("ai_processing_queue") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** Why it was queued, which is what the run will be recorded under. */
    val reason = enumerationByName<ClassificationReason>("reason", 32)

    val enqueuedAt = timestamp("enqueued_at")

    /**
     * How many times the agent has tried and failed on this mail.
     *
     * Counted rather than dropped on the first failure, because the usual failure is the backend
     * being down for a minute, and counted rather than retried forever, because the other failure is
     * a mail that breaks something and would otherwise block everything behind it. Past the limit
     * the row stays, out of the way, with [lastError] on it -- a queue that silently drops its
     * failures is a queue nobody can debug.
     */
    val attempts = integer("attempts").default(0)

    /** What went wrong the last time, null while nothing has. */
    val lastError = text("last_error").nullable()

    init {
        // One place in the queue per mail. Asking twice for the same mail is not two runs, and two
        // callers asking at the same moment must not make it one either.
        uniqueIndex(email)
    }
}

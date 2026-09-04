package es.jvbabi.overmail.server.jobs.preview

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Email
import es.jvbabi.overmail.server.database.models.EmailPreviews
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.util.mailPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * How many mails wait in the queue at most.
 *
 * Bounded on purpose: [backfill] fills it and suspends once it is full, so a mailbox with a
 * hundred thousand mails in it is worked through a thousand at a time instead of being read into
 * memory as one list of ids.
 */
private const val CAPACITY = 1_000

/** How many ids one round of the backfill reads. */
private const val BATCH_SIZE = 500

/**
 * Queue of mails whose preview has to be worked out, and the backfill that finds them.
 *
 * A mail that is imported gets its preview where it is written -- the body is parsed at that
 * moment anyway, see `EmailImporter`. This is for the ones that were stored before there was a
 * preview at all, and for anything that has to be redone later: [enqueue] takes a single mail,
 * [backfill] takes every mail that has no preview row yet.
 *
 * One consumer, one body at a time: a preview is a whole mail body read out of the database and
 * an HTML parse on top, and there is no reason to hold more than one of those at once.
 */
class EmailPreviewQueue(private val database: OvermailDatabase) {

    private val logger = LoggerFactory.getLogger(EmailPreviewQueue::class.java)

    /** Mails waiting in [channel] or being worked on, so nothing is queued twice. */
    private val pending = ConcurrentHashMap.newKeySet<Email.Id>()

    private val channel = Channel<Email.Id>(capacity = CAPACITY)

    /** Takes a mail unless it is already waiting. Never suspends, never blocks a writer. */
    fun enqueue(emailId: Email.Id) {
        if (!pending.add(emailId)) return
        // A full queue means the backfill is running; that one will reach this mail anyway.
        if (channel.trySend(emailId).isFailure) pending.remove(emailId)
    }

    /**
     * Puts every mail without a preview into the queue, oldest first, and suspends whenever it is
     * full. Runs once at startup and then has nothing to do.
     *
     * What bounds it: every mail it hands over comes back with a row, empty body or not, so the
     * set of mails without one only shrinks.
     */
    suspend fun backfill() {
        var queued = 0

        try {
            while (true) {
                val ids = database.query {
                    Emails
                        .join(EmailPreviews, JoinType.LEFT, Emails.id, EmailPreviews.email)
                        .select(Emails.id)
                        .where { EmailPreviews.email.isNull() }
                        .limit(BATCH_SIZE)
                        .map { row -> row[Emails.id].value }
                }

                if (ids.isEmpty()) break

                for (id in ids) {
                    if (!pending.add(id)) continue
                    // Suspends while the queue is full, which is the backpressure: the reader
                    // above only runs again once the consumer has worked through what it sent.
                    channel.send(id)
                    queued++
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // Not rethrown: the app serves mail perfectly well without previews, a listing just
            // shows the subject alone. The next start tries again.
            logger.warn("Could not queue mails for a preview: ${cause.message}")
        }

        if (queued > 0) logger.info("Queued $queued mail(s) for a preview")
    }

    /** Works through the queue until it is closed. Suspends while it is empty. */
    suspend fun consume() {
        for (emailId in channel) {
            try {
                write(emailId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                logger.warn("Could not work out the preview of $emailId: ${cause.message}")
            } finally {
                pending.remove(emailId)
            }
        }
    }

    private suspend fun write(emailId: Email.Id) {
        // Two columns of one row rather than the entity, which would read the raw source with it.
        val body = database.query {
            Emails
                .select(Emails.textContent, Emails.htmlContent)
                .where { Emails.id eq emailId }
                .firstOrNull()
                ?.let { row -> row[Emails.textContent] to row[Emails.htmlContent] }
        } ?: return

        // Parsing HTML is processor work, so it happens off the dispatcher the queries run on.
        val preview = withContext(Dispatchers.Default) { mailPreview(body.first, body.second) }

        database.query {
            EmailPreviews.upsert {
                it[email] = emailId
                it[EmailPreviews.preview] = preview
            }
        }
    }
}

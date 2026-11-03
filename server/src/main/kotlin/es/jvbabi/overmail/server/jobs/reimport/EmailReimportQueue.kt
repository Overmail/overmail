package es.jvbabi.overmail.server.jobs.reimport

import es.jvbabi.overmail.server.database.models.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Mails waiting to be imported again, one at a time.
 *
 * A re-import reads a whole mailbox and can take a while, so the route only queues it. One consumer
 * (`startJobs` in `AppModule`) is enough: it is a manual action, and one at a time keeps two of
 * them from opening a connection each to the same mailbox.
 */
class EmailReimportQueue(private val reimporter: EmailReimporter) {
    private val logger = LoggerFactory.getLogger(EmailReimportQueue::class.java)

    /** Ids waiting in [channel], so a mail asked for twice is read once. */
    private val pending = ConcurrentHashMap.newKeySet<Email.Id>()
    private val channel = Channel<Email.Id>(capacity = Channel.UNLIMITED)

    fun enqueue(emailId: Email.Id) {
        if (!pending.add(emailId)) return
        if (channel.trySend(emailId).isFailure) pending.remove(emailId)
    }

    suspend fun consume() {
        for (emailId in channel) {
            pending.remove(emailId)
            try {
                when (val result = reimporter.reimport(emailId)) {
                    is EmailReimporter.Result.Reimported -> logger.info("Re-imported mail $emailId from ${result.folder}")
                    EmailReimporter.Result.NotFound -> logger.warn("Could not re-import mail $emailId: no folder of its inbox holds it")
                    EmailReimporter.Result.Gone -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Re-importing mail $emailId failed", e)
            }
        }
    }
}

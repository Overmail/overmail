package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** How many pushes wait at most. One that does not fit is dropped: the app catches up when opened. */
private const val CAPACITY = 1_000

/**
 * Queue of pushes on their way out.
 *
 * [enqueue] never waits, so whoever has something to announce -- an import step, a route -- is
 * not held up by Firebase. [consume] resolves each target into the devices behind it and hands the
 * push to the [sender], once per device.
 *
 * Kept in memory on purpose. A push only says "ask the server"; one lost in a restart costs a
 * notification, not a mail.
 *
 * @param retryDelays how long to wait before each further attempt at a push that failed for a
 *   reason that may pass. One consumer, so a long list here holds up everything behind it.
 */
class PushQueue(
    private val database: OvermailDatabase,
    private val sender: PushSender,
    private val retryDelays: List<Duration> = listOf(1.seconds, 5.seconds),
) {

    private val logger = LoggerFactory.getLogger(PushQueue::class.java)

    private data class Entry(val target: PushTarget, val message: PushMessage)

    /** One device a push goes to. */
    private data class Recipient(val sessionId: Uuid, val userId: Uuid, val token: String)

    private val channel = Channel<Entry>(capacity = CAPACITY)

    /** Takes a push for [target]. Never suspends, never blocks a writer. */
    fun enqueue(target: PushTarget, message: PushMessage) {
        // Nothing would ever send it, so it is not worth a place in the queue.
        if (sender === PushSender.Disabled) return
        if (channel.trySend(Entry(target, message)).isFailure) {
            logger.warn("The push queue is full, dropping a push for $target")
        }
    }

    /** Works through the queue until it is closed. Suspends while it is empty. */
    suspend fun consume() {
        for ((target, message) in channel) {
            try {
                val recipients = recipientsOf(target)
                val delivered = recipients.count { deliver(it, message) }
                if (recipients.isNotEmpty()) {
                    logger.info("Pushed ${message::class.simpleName} for $target to $delivered of ${recipients.size} device(s)")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                logger.warn("Could not send a push to $target: ${cause.message}")
            }
        }
    }

    /** Whether the push was handed over. One that was not is logged here, or its token forgotten. */
    private suspend fun deliver(recipient: Recipient, message: PushMessage): Boolean {
        val data = message.toData(recipient.userId)

        var attempt = 0
        while (true) {
            when (val result = sender.send(recipient.token, data, message.isUrgent)) {
                PushSender.Result.Sent -> return true
                PushSender.Result.Unregistered -> {
                    forgetToken(recipient)
                    return false
                }
                is PushSender.Result.Failed -> {
                    val wait = retryDelays.getOrNull(attempt++).takeIf { result.isRetryable }
                    if (wait == null) {
                        logger.warn("Giving up on a push to session ${recipient.sessionId}: ${result.reason}")
                        return false
                    }
                    delay(wait)
                }
            }
        }
    }

    /**
     * The devices behind [target]: its sessions that still work and have a token. A token is sent
     * to once, even if two sessions of the same device hold it.
     */
    private suspend fun recipientsOf(target: PushTarget): List<Recipient> = database.query {
        Sessions
            .select(Sessions.id, Sessions.user, Sessions.client)
            .where {
                when (target) {
                    PushTarget.Everyone -> Sessions.revokedAt.isNull()
                    is PushTarget.User -> (Sessions.user eq target.userId) and Sessions.revokedAt.isNull()
                    is PushTarget.Session -> (Sessions.id eq target.sessionId) and Sessions.revokedAt.isNull()
                }
            }
            .mapNotNull { row ->
                val token = (row[Sessions.client] as? Session.Client.Android)?.firebaseToken ?: return@mapNotNull null
                Recipient(sessionId = row[Sessions.id].value, userId = row[Sessions.user].value, token = token)
            }
            .distinctBy { it.token }
    }

    /** Drops a token Firebase no longer knows, so it is not sent to again. */
    private suspend fun forgetToken(recipient: Recipient) {
        database.query {
            val client = Sessions.select(Sessions.client).where { Sessions.id eq recipient.sessionId }.firstOrNull()?.get(Sessions.client)
            // Only the token that failed: the app may have registered a new one in the meantime.
            if (client !is Session.Client.Android || client.firebaseToken != recipient.token) return@query
            Sessions.update({ Sessions.id eq recipient.sessionId }) { it[Sessions.client] = client.copy(firebaseToken = null) }
        }
        logger.info("Session ${recipient.sessionId} no longer has a push token")
    }
}

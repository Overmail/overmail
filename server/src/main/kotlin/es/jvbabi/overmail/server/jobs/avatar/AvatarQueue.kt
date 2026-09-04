package es.jvbabi.overmail.server.jobs.avatar

import es.jvbabi.overmail.server.data.avatar.AvatarLookup
import es.jvbabi.overmail.server.data.avatar.circlePadding
import es.jvbabi.overmail.server.data.notifier.AvatarNotifier
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailAvatar
import es.jvbabi.overmail.server.database.models.EmailUser
import es.jvbabi.overmail.server.util.maskEmail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** How many addresses may be in flight at once. Each one is a request to a third party. */
private const val CONCURRENCY = 10

/**
 * How long an address nobody had a picture for is left alone. Without it every stack batch would
 * ask the same third parties about the same senders again, since a miss leaves no row behind.
 */
private val RETRY_UNRESOLVED_AFTER = 6.hours

/**
 * Queue of address book entries waiting for their picture to be looked up.
 *
 * A lookup is one or more requests to a third party, so it never happens inside a request that
 * serves mail: the stack socket enqueues the senders of the batch it just sent and the pictures
 * arrive afterwards, over that same socket, through [AvatarNotifier].
 *
 * [enqueue] is safe to call from any thread or coroutine; [consume] is meant to be run by a single
 * consumer coroutine (see `startJobs` in `AppModule`), which then works through [CONCURRENCY]
 * addresses at a time.
 */
class AvatarQueue(
    private val database: OvermailDatabase,
    private val avatarLookup: AvatarLookup,
    private val avatarNotifier: AvatarNotifier,
    private val mailNotifier: MailNotifier,
) {
    private val logger = LoggerFactory.getLogger(AvatarQueue::class.java)

    /** Address rows currently waiting in [channel] or being looked up. */
    private val pending = ConcurrentHashMap.newKeySet<EmailUser.Id>()

    /**
     * When an address was last looked up without finding anything. In memory only: a restart is
     * as good a moment as any to try the ones that failed again.
     */
    private val unresolved = ConcurrentHashMap<EmailUser.Id, Instant>()

    private val channel = Channel<EmailUser.Id>(capacity = Channel.UNLIMITED)

    /**
     * Enqueues an address book entry for a lookup, unless one is already queued for it or a recent
     * one came back empty. Callers do not have to check whether a picture is already linked --
     * [resolve] reads that again anyway, right before it would go out to the network.
     */
    fun enqueue(emailUserId: EmailUser.Id) {
        val lastMiss = unresolved[emailUserId]
        if (lastMiss != null && lastMiss > Clock.System.now() - RETRY_UNRESOLVED_AFTER) return

        if (!pending.add(emailUserId)) return
        if (channel.trySend(emailUserId).isFailure) pending.remove(emailUserId)
    }

    /** Works through the queue until it is closed. Suspends while it is empty. */
    suspend fun consume() = coroutineScope {
        val slots = Semaphore(CONCURRENCY)

        for (emailUserId in channel) {
            // Taken before launching, so the loop cannot pile up coroutines that all wait for a
            // slot: ten lookups are in flight, everything else stays in the channel.
            slots.acquire()
            launch {
                try {
                    resolve(emailUserId)
                } finally {
                    // Only released once the lookup is over, unlike in EmailClassificationQueue:
                    // re-queueing an address that is being looked up right now buys nothing.
                    pending.remove(emailUserId)
                    slots.release()
                }
            }
        }
    }

    /** Looks one address up and links what was found. A miss is remembered, see [unresolved]. */
    private suspend fun resolve(emailUserId: EmailUser.Id) {
        try {
            // Read again rather than trusted from the caller: the entry may have been resolved
            // between being enqueued and getting a slot, which is the common case for a sender
            // that appears in several batches.
            val address = database.query {
                EmailUser.findById(emailUserId)?.takeIf { it.avatarId == null }?.address
            } ?: return

            val found = avatarLookup.findAvatarOnline(address)

            if (found == null) {
                unresolved[emailUserId] = Clock.System.now()
                logger.debug("No avatar found for ${address.maskEmail()}")
                return
            }

            // Decoding and reading a picture is processor work, so it happens here rather than
            // inside the transaction below, and off the IO dispatcher that one runs on. A format
            // nothing can decode gets no padding, see EmailAvatars.circlePadding.
            val circlePadding = withContext(Dispatchers.Default) { found.data.circlePadding() } ?: 0.0

            val stored = database.query {
                val emailUser = EmailUser.findById(emailUserId) ?: return@query null
                val avatar = EmailAvatar.new {
                    this.data = found.data
                    this.avatarSource = found.source
                    this.circlePadding = circlePadding
                }
                emailUser.avatar = avatar
                // The owner is read here, inside the transaction that has the row: the notifier
                // below is per user, and a reference does not resolve once this is closed.
                avatar.id.value to emailUser.user.id.value
            } ?: return
            val (avatarId, userId) = stored

            avatarNotifier.notifyAvatarResolved(emailUserId, address, avatarId, circlePadding)
            // Every mail of this sender shows the picture now, so whoever has one on screen has
            // to read it again.
            mailNotifier.notifySenderChanged(userId, emailUserId)

            logger.info(
                "Found a ${found.data.size} byte avatar for ${address.maskEmail()} via " +
                        "${found.source}, needs ${"%.1f".format(circlePadding * 100)}% padding for a circle"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // Not rethrown: one address that cannot be reached must not take the consumer down
            // with it. It stays unlinked, so the next batch it appears in enqueues it again.
            logger.warn("Could not resolve an avatar: ${cause.message}")
        }
    }
}

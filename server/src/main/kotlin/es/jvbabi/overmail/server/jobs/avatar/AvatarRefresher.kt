package es.jvbabi.overmail.server.jobs.avatar

import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailAvatarRepository
import es.jvbabi.overmail.server.domain.repository.EmailUserRepository
import es.jvbabi.overmail.server.domain.repository.icon.EmailIconRepository
import es.jvbabi.overmail.server.util.maskEmail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** How many addresses may be in flight at once. Each one is a request to a third party. */
private const val CONCURRENCY = 10

/**
 * Fills the avatar cache on demand.
 *
 * Nothing here runs on its own. A lookup is one or more requests to a third party per address, and
 * a mailbox has thousands of them, so the whole thing is a button rather than a background job:
 * importing mail stays free of network calls to strangers, and it is visible when they happen.
 *
 * A refresh runs detached from the request that asked for it -- a real address book takes minutes,
 * which is longer than any client will hold a connection open -- and reports how far it got through
 * [progressOf].
 */
class AvatarRefresher(
    private val emailUserRepository: EmailUserRepository,
    private val avatarRepository: EmailAvatarRepository,
    private val iconRepository: EmailIconRepository,
    private val coroutineScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(AvatarRefresher::class.java)

    /**
     * The last run per user, finished or not. Kept after it finished so the counts of it can still
     * be read; a new run for the same user replaces it.
     */
    private val runs = ConcurrentHashMap<Uuid, Run>()

    /** How far a refresh got. [running] false means these are the final numbers of that run. */
    data class Progress(
        val running: Boolean,
        /** Whether it was the whole address book rather than only the addresses without a picture. */
        val all: Boolean,
        val total: Int,
        val done: Int,
        val found: Int,
    )

    private class Run(val all: Boolean, val total: Int) {
        val done = AtomicInteger()
        val found = AtomicInteger()

        @Volatile
        var running = true

        fun progress() = Progress(
            running = running,
            all = all,
            total = total,
            done = done.get(),
            found = found.get(),
        )
    }

    /** The refresh of [user], or null when they have never asked for one. */
    fun progressOf(user: User): Progress? = runs[user.id]?.progress()

    /**
     * Starts a refresh of [user]'s address book and returns right away, before a single picture has
     * been downloaded -- watch [progressOf] for the rest.
     *
     * @param all every address, throwing away the pictures we hold for them first. False only
     *   visits the addresses no picture was ever found for, which leaves the ids of the existing
     *   ones -- and with them everything browsers have cached under those urls -- alone.
     * @return the progress the run starts out with, or null when one is already going for [user].
     */
    suspend fun start(user: User, all: Boolean): Progress? {
        // The list is taken before the run is registered, so a client polling right after this
        // sees a total rather than a zero that has not been filled in yet.
        val addresses =
            if (all) emailUserRepository.distinctAddresses(user).first()
            else emailUserRepository.distinctAddressesWithoutAvatar(user).first()

        val run = Run(all = all, total = addresses.size)
        // Only replaces a run that is over: two clicks must not have two runs writing the same
        // address book at once.
        val current = runs.compute(user.id) { _, existing ->
            if (existing != null && existing.running) existing else run
        }
        if (current !== run) return null

        logger.info(
            "Refreshing ${addresses.size} avatars for ${user.username}" +
                "${if (all) ", the whole address book" else ", the missing ones"}, " +
                "$CONCURRENCY at a time"
        )

        coroutineScope.launch {
            try {
                // After the list was taken, so an address whose picture is dropped here is still
                // one of the addresses being visited.
                if (all) {
                    val dropped = avatarRepository.deleteForUser(user)
                    logger.info("Dropped $dropped cached avatars of ${user.username}")
                }

                resolveAll(user, addresses, run)

                logger.info(
                    "Refreshed ${run.found.get()} of ${addresses.size} avatars for ${user.username}"
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                logger.warn("Avatar refresh for ${user.username} failed", cause)
            } finally {
                run.running = false
            }
        }

        return run.progress()
    }

    private suspend fun resolveAll(user: User, addresses: List<String>, run: Run) = coroutineScope {
        val slots = Semaphore(CONCURRENCY)

        addresses
            .map { address ->
                async {
                    slots.withPermit { resolve(user, address, run) }
                    run.done.incrementAndGet()
                }
            }
            .awaitAll()
    }

    /** Looks [address] up and links what was found. A failure is left unlinked, so it is retried. */
    private suspend fun resolve(user: User, address: String, run: Run) {
        try {
            val found = iconRepository.findIconOnline(address)

            if (found == null) {
                logger.debug("No avatar found for ${address.maskEmail()}")
                return
            }

            val id = avatarRepository.insert(image = found.data, source = found.source)
            emailUserRepository.linkAvatar(user, address, id)
            run.found.incrementAndGet()

            logger.info(
                "Found a ${found.data.size} byte avatar for ${address.maskEmail()} via ${found.source}"
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            // Not rethrown: one address that cannot be reached must not take the rest of the run
            // down with it. It stays unlinked, which is exactly what the next refresh looks for.
            logger.warn("Could not resolve an avatar for ${address.maskEmail()}: ${cause.message}")
        }
    }
}

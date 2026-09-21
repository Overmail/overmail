package es.jvbabi.overmail.data.cache

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("CachedResource")

/**
 * Something the server owns and the local database keeps a copy of.
 *
 * What is emitted is always the database, never the server's answer itself: the answer is
 * [persist]ed, and the database flow picks it up like any other write. So there is one source of
 * truth, and a later write from anywhere else shows up as well.
 *
 * @param local the cached copy; expected to emit again whenever the data behind it changes, which
 *   is what a Room `Flow` query does.
 * @param fetch asks the server, see `safeRequest { }`.
 * @param persist writes a successful answer into what [local] reads.
 * @param remoteTimeout how long [stream] waits for the server before it settles for the cache.
 */
class CachedResource<Local, Remote>(
    private val local: () -> Flow<Local>,
    private val fetch: suspend () -> Result<Remote>,
    private val persist: suspend (Remote) -> Unit,
    private val remoteTimeout: Duration = 5.seconds,
) {
    /**
     * Fetches from the server once per collection and emits the cache for as long as it is
     * collected.
     *
     * With [instantLocalEmission] the cache is emitted right away and the server's answer follows
     * as a second emission. Without it the first emission waits for the answer -- until it is
     * persisted, the request failed or [remoteTimeout] passed, whichever comes first. A timeout
     * does not cancel the request: when the answer turns up later it still lands in the cache,
     * and from there in the flow.
     */
    fun stream(instantLocalEmission: Boolean): Flow<Local> = channelFlow {
        val remoteSettled = CompletableDeferred<Unit>()

        launch {
            try {
                fetch()
                    .onSuccess { persist(it) }
                    .onFailure { logger.w(it) { "Fetching from the server failed, staying on the cache" } }
            } finally {
                remoteSettled.complete(Unit)
            }
        }

        if (!instantLocalEmission) withTimeoutOrNull(remoteTimeout) { remoteSettled.await() }

        local().collect { send(it) }
    }
}

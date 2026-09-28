package es.jvbabi.overmail.data.picture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs renders one at a time. Each builds a web view and draws a whole mail on the main thread;
 * side by side they hold up every frame while they run. Of those waiting, the one with the lowest
 * order goes next -- asked again every time, since a mail waiting for its turn moves up the pile.
 */
internal class RenderQueue {
    private class Waiter(val order: () -> Int) {
        val turn = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private val waiting = mutableListOf<Waiter>()
    private var busy = false

    suspend fun <T> run(order: () -> Int, render: suspend () -> T): T {
        val waiter = Waiter(order)
        lock.withLock {
            waiting += waiter
            startNext()
        }
        try {
            waiter.turn.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    // Gone before its turn, or cancelled just as it came: then the turn is passed on.
                    if (!waiting.remove(waiter)) {
                        busy = false
                        startNext()
                    }
                }
            }
            throw e
        }
        try {
            return render()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    busy = false
                    startNext()
                }
            }
        }
    }

    /** Only with [lock] held. */
    private fun startNext() {
        if (busy) return
        val next = waiting.minByOrNull { it.order() } ?: return
        waiting.remove(next)
        busy = true
        next.turn.complete(Unit)
    }
}

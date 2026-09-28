package es.jvbabi.overmail.data.picture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderQueueTest {

    @Test
    fun rendersOneAtATimeTheLowestOrderFirst() = runTest {
        val queue = RenderQueue()
        val started = mutableListOf<String>()
        val first = CompletableDeferred<Unit>()
        launch { queue.run({ 5 }) { started += "busy"; first.await() } }
        runCurrent()

        // Asked for bottom card first, the way the first deal composes them.
        var orderOfC = 3
        launch { queue.run({ 2 }) { started += "b" } }
        launch { queue.run({ orderOfC }) { started += "c" } }
        launch { queue.run({ 1 }) { started += "a" } }
        runCurrent()
        assertEquals(listOf("busy"), started)

        // Moved up the pile while it waited.
        orderOfC = 0
        first.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "c", "a", "b"), started)
    }

    @Test
    fun aCancelledRenderPassesItsTurnOn() = runTest {
        val queue = RenderQueue()
        val started = mutableListOf<String>()
        val busy = launch { queue.run({ 0 }) { started += "busy"; CompletableDeferred<Unit>().await() } }
        runCurrent()
        val gone = launch { queue.run({ 0 }) { started += "gone" } }
        launch { queue.run({ 1 }) { started += "next" } }
        runCurrent()

        gone.cancel()
        busy.cancel()
        runCurrent()
        assertEquals(listOf("busy", "next"), started)
    }
}

package es.jvbabi.overmail.ui.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailSnapshotQueueTest {

    @Test
    fun rendersOneAtATimeTheLowestOrderFirst() = runTest {
        val started = mutableListOf<String>()
        val first = CompletableDeferred<Unit>()
        launch { EmailSnapshotQueue.run({ 5 }) { started += "busy"; first.await() } }
        runCurrent()

        // Asked for bottom card first, the way the first deal composes them.
        var orderOfC = 3
        launch { EmailSnapshotQueue.run({ 2 }) { started += "b" } }
        launch { EmailSnapshotQueue.run({ orderOfC }) { started += "c" } }
        launch { EmailSnapshotQueue.run({ 1 }) { started += "a" } }
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
        val started = mutableListOf<String>()
        val busy = launch { EmailSnapshotQueue.run({ 0 }) { started += "busy"; CompletableDeferred<Unit>().await() } }
        runCurrent()
        val gone = launch { EmailSnapshotQueue.run({ 0 }) { started += "gone" } }
        launch { EmailSnapshotQueue.run({ 1 }) { started += "next" } }
        runCurrent()

        gone.cancel()
        busy.cancel()
        runCurrent()
        assertEquals(listOf("busy", "next"), started)
    }
}

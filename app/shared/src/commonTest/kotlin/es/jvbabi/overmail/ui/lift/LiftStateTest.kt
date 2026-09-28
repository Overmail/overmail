package es.jvbabi.overmail.ui.lift

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import es.jvbabi.overmail.ui.components.ScrollOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEADZONE = 10f

/** Long enough for every spring of the lift to have settled. */
private const val SETTLED = 3_000L

class LiftStateTest {

    @Test
    fun liftRaisesTheContentAndIsFelt() = liftTest { state, haptics ->
        state.lift("a")
        runCurrent()

        assertEquals("a", state.lifted)
        assertTrue(state.isLifted("a"))
        assertFalse(state.isLifted("b"))
        assertTrue(state.isHeld)
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics.performed)

        advanceTimeBy(SETTLED)
        assertEquals(1f, state.progress)
    }

    @Test
    fun liftStartsAtTheTopOfTheContent() = liftTest { state, _ ->
        val scroll = state.register("a")
        scroll.scrollTo(500f)

        state.lift("a")

        assertEquals(0f, scroll.value)
    }

    @Test
    fun fingerWithinTheDeadzoneDoesNotScroll() = liftTest { state, _ ->
        val scroll = state.register("a")
        state.lift("a")
        state.moveFinger(DEADZONE)
        state.moveFinger(-2 * DEADZONE)
        state.moveFinger(DEADZONE / 2)

        advanceTimeBy(1_000)

        assertEquals(-DEADZONE / 2, state.fingerY)
        assertEquals(0f, scroll.value)
    }

    @Test
    fun fingerBelowScrollsDownAtTheSpeedOfTheCurve() = liftTest { state, _ ->
        val scroll = state.register("a")
        state.lift("a")
        state.moveFinger(DEADZONE + 20f)

        advanceTimeBy(1_000)

        val expected = liftScrollVelocity(DEADZONE + 20f, DEADZONE)
        // One frame of it is spent finding out when the first frame was.
        assertTrue(scroll.value in expected * 0.95f..expected, "scrolled ${scroll.value}, expected about $expected")
    }

    @Test
    fun fingerAboveScrollsBackUpAndStopsAtTheTop() = liftTest { state, _ ->
        val scroll = state.register("a")
        state.lift("a")
        state.moveFinger(DEADZONE + 20f)
        advanceTimeBy(1_000)
        state.moveFinger(-2 * (DEADZONE + 20f))

        advanceTimeBy(5_000)

        assertEquals(0f, scroll.value)
    }

    @Test
    fun releasePutsTheContentDownScrolledBackToItsTop() = liftTest { state, _ ->
        val scroll = state.register("a")
        state.lift("a")
        state.moveFinger(DEADZONE + 50f)
        advanceTimeBy(1_000)
        assertTrue(scroll.value > 0f)

        state.release()
        runCurrent()

        assertFalse(state.isHeld)
        assertEquals(0f, state.fingerY)
        // Still drawn over everything while it is on its way down.
        assertEquals("a", state.lifted)

        advanceTimeBy(SETTLED)

        assertNull(state.lifted)
        assertEquals(0f, state.progress)
        assertEquals(0f, scroll.value)
    }

    @Test
    fun fingerMovesAfterReleaseAreIgnored() = liftTest { state, _ ->
        val scroll = state.register("a")
        state.lift("a")
        state.release()
        state.moveFinger(DEADZONE + 50f)

        advanceTimeBy(1_000)

        assertEquals(0f, state.fingerY)
        assertEquals(0f, scroll.value)
    }

    @Test
    fun releaseWithNothingLiftedDoesNothing() = liftTest { state, _ ->
        state.release()
        advanceTimeBy(SETTLED)

        assertNull(state.lifted)
        assertEquals(0f, state.progress)
    }

    @Test
    fun liftingSomethingElseOnTheWayDownTakesOver() = liftTest { state, haptics ->
        state.lift("a")
        advanceTimeBy(SETTLED)
        state.release()
        advanceTimeBy(50)

        state.lift("b")
        advanceTimeBy(SETTLED)

        assertEquals("b", state.lifted)
        assertTrue(state.isHeld)
        assertEquals(1f, state.progress)
        assertEquals(2, haptics.performed.size)
    }

    @Test
    fun scrollVelocityIsZeroInsideTheDeadzone() {
        assertEquals(0f, liftScrollVelocity(0f, DEADZONE))
        assertEquals(0f, liftScrollVelocity(DEADZONE, DEADZONE))
        assertEquals(0f, liftScrollVelocity(-DEADZONE, DEADZONE))
    }

    @Test
    fun scrollVelocityGrowsWithTheDistancePastTheDeadzone() {
        assertEquals(10f * SCROLL_SPEED * SCROLL_SPEED, liftScrollVelocity(DEADZONE + 10f, DEADZONE))
        assertEquals(20f * SCROLL_SPEED * SCROLL_SPEED, liftScrollVelocity(DEADZONE + 20f, DEADZONE))
        assertEquals(-10f * SCROLL_SPEED * SCROLL_SPEED, liftScrollVelocity(-DEADZONE - 10f, DEADZONE))
    }

    @Test
    fun targetIsTheHostLessItsEdges() {
        val host = Rect(0f, 0f, 400f, 800f)

        assertEquals(Rect(16f, 40f, 384f, 750f), liftTarget(host, left = 16f, top = 40f, right = 16f, bottom = 50f))
    }

    @Test
    fun boundsGoFromTheRestingPlaceToTheTarget() {
        val resting = Rect(20f, 300f, 380f, 500f)
        val target = Rect(16f, 40f, 384f, 750f)

        assertEquals(resting, liftedBounds(resting, target, 0f))
        assertEquals(target, liftedBounds(resting, target, 1f))
        assertEquals(Rect(18f, 170f, 382f, 625f), liftedBounds(resting, target, 0.5f))
    }

    @Test
    fun onTheWayTheContentIsScaledToTheWidthAndCutOffAtTheHeight() {
        val target = Rect(16f, 40f, 384f, 740f)

        assertEquals(LiftedCut(target, 1f, Size(368f, 700f)), liftedCut(target, target))
        // Half as wide and a tenth as tall: shown at half its size, a fifth of it from the top.
        assertEquals(LiftedCut(Rect(0f, 0f, 184f, 70f), 0.5f, Size(368f, 140f)), liftedCut(target, Rect(0f, 0f, 184f, 70f)))
    }
}

private fun LiftState.register(key: Any): ScrollOffset {
    val scroll = ScrollOffset().apply { max = 10_000f }
    targets[key] = LiftTarget(layer = null).also { it.scroll = scroll }
    return scroll
}

private class FakeHaptics : HapticFeedback {
    val performed = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        performed += hapticFeedbackType
    }
}

/** Frames every 16 ms of the test's virtual time, which is what drives the springs and the scroll. */
private class TestFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        delay(16)
        return onFrame(scheduler.currentTime * 1_000_000)
    }
}

private fun liftTest(block: suspend TestScope.(LiftState, FakeHaptics) -> Unit) = runTest {
    val haptics = FakeHaptics()
    val scope = CoroutineScope(backgroundScope.coroutineContext + TestFrameClock(testScheduler))
    block(LiftState(scope, haptics, DEADZONE), haptics)
}

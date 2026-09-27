package es.jvbabi.overmail.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * How far content that is taller than its box has been scrolled, in px, for content that is
 * scrolled for the user rather than by them -- it takes no touch, something else moves it. The
 * content says how far it can go, [max]; whoever moves it stays within that.
 */
@Stable
class ScrollOffset {
    var value by mutableFloatStateOf(0f)
        private set

    private var maxState by mutableFloatStateOf(0f)

    /** How much taller the content is than its box; 0 for content that fits. Set by the content. */
    var max: Float
        get() = maxState
        internal set(max) {
            maxState = max.coerceAtLeast(0f)
            value = value.coerceIn(0f, maxState)
        }

    /** Whether there is more of the content below what shows. */
    val hasMoreBelow: Boolean get() = value < max - 0.5f

    fun scrollBy(delta: Float) {
        value = (value + delta).coerceIn(0f, max)
    }

    fun scrollTo(offset: Float) {
        value = offset.coerceIn(0f, max)
    }
}

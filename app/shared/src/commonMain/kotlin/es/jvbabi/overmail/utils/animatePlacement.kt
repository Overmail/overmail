package es.jvbabi.overmail.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import kotlinx.coroutines.launch

/**
 * Glides the element to where its parent puts it instead of jumping there -- for a layout that
 * reflows, like a [androidx.compose.foundation.layout.FlowRow] whose items wrap to another line
 * when one before them comes or goes. Where it is first placed it simply is.
 */
fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }

    this
        .onPlaced { target = it.positionInParent().round() }
        .offset {
            val placed = target ?: return@offset IntOffset.Zero
            val current = animatable ?: Animatable(placed, IntOffset.VectorConverter).also { animatable = it }
            if (current.targetValue != placed) scope.launch {
                current.animateTo(placed, spring(stiffness = Spring.StiffnessMediumLow))
            }
            // Drawn where it was, pulled towards where it now is.
            current.value - placed
        }
}

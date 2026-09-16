@file:OptIn(ExperimentalMaterial3Api::class)

package es.jvbabi.overmail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeProgressive
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeEffect

/** Which side of the scrolling content a scrim sits on, and therefore which way it fades out. */
enum class ScrimEdge {
    Top,
    Bottom,
}

/**
 * Content pinned to one edge of a scrolling area, with whatever scrolls behind it blurred out.
 *
 * The blur fades towards the scrolling content instead of ending on a hard line, so items pass
 * under it rather than disappearing at an edge. Pair it with [dev.chrisbanes.haze.hazeSource] on the
 * scrolling content and the same [hazeState]; the scrolling content also needs padding on this edge
 * worth this scrim's height, otherwise its last items can never be scrolled out from under it.
 *
 * @param containerColor the surface the scrim sits on. It has to match, as the gradient fades to it.
 */
@Composable
fun ProgressiveBlurScrim(
    hazeState: HazeState,
    edge: ScrimEdge,
    modifier: Modifier = Modifier,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hazeStyle = HazeMaterials.thin(containerColor = containerColor)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .hazeEffect(hazeState) {
                blurEffect {
                    blurRadius = BLUR_RADIUS
                    style = hazeStyle
                    progressive = when (edge) {
                        ScrimEdge.Top -> HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                        ScrimEdge.Bottom -> HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
                    }
                }
            }
            .background(
                when (edge) {
                    // Opaque behind the content itself, transparent where the scrolling area shows
                    // through, so the fade lines up with the blur.
                    ScrimEdge.Top -> Brush.verticalGradient(
                        OPAQUE_UNTIL to containerColor.copy(alpha = 1f),
                        1f to containerColor.copy(alpha = 0f),
                    )

                    ScrimEdge.Bottom -> Brush.verticalGradient(
                        0f to containerColor.copy(alpha = 0f),
                        1f - OPAQUE_UNTIL to containerColor.copy(alpha = 1f),
                    )
                }
            ),
        content = content,
    )
}

private val BLUR_RADIUS = 24.dp

/** How much of the scrim is fully opaque before the gradient starts fading. */
private const val OPAQUE_UNTIL = 0.25f

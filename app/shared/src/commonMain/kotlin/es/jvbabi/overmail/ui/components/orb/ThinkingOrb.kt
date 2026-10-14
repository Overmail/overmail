package es.jvbabi.overmail.ui.components.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ui.theme.AppTheme
import kotlin.math.max
import kotlin.math.min

/**
 * One of the thinking-orbs animations, see `OrbEngine.kt`: the dots of each frame drawn onto a
 * canvas of [size]'s design size in dp, the way the web app's `ThinkingOrb.svelte` draws them in
 * css pixels.
 *
 * Decoration next to a text that says the same thing, so it carries no content description.
 */
@Composable
fun ThinkingOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    size: OrbSize = OrbSize.Inline,
) {
    val orb = remember(state, size) { resolveOrb(state, size) }
    // The orb paints its ink in the opposite of what it lies on.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var time by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(orb) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> time = (now - start) / 1e9 * orb.speed }
    }

    Canvas(modifier.size(size.px.dp)) {
        val scale = this.size.width / size.px
        for (dot in orb.frame(size.px.toDouble(), time)) {
            val ink = min(1.0, max(0.0, dot.white)).let { if (isDark) 1 - it else it }.toFloat()
            drawCircle(
                color = Color(ink, ink, ink, dot.a.toFloat().coerceIn(0f, 1f)),
                radius = (dot.r * scale).toFloat(),
                center = Offset((dot.x * scale).toFloat(), (dot.y * scale).toFloat()),
            )
        }
    }
}

@Composable
@Preview
private fun ThinkingOrbPreview() {
    AppTheme(dynamicColor = false) {
        Row {
            OrbState.entries.forEach { ThinkingOrb(it, size = OrbSize.Avatar) }
        }
    }
}

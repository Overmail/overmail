package es.jvbabi.overmail.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * A label's color as the fill behind its name, the web app's tinted badge: the hue is the label's,
 * the lightness is the theme's. A label color is derived from its name and knows nothing about the
 * theme, so taking it as-is could leave a fill the text is unreadable on. Saturation is capped, so
 * the chip stays a surface rather than a shout.
 */
@Composable
fun Color.labelContainerColor(): Color =
    withThemeLightness(light = 0.90f, dark = 0.26f, maxSaturation = 0.45f)

/** The same hue dark enough (or light enough) to stand as an icon on the surface itself. */
@Composable
fun Color.labelContentColor(): Color =
    withThemeLightness(light = 0.45f, dark = 0.75f, maxSaturation = 0.6f)

@Composable
private fun Color.withThemeLightness(light: Float, dark: Float, maxSaturation: Float): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (hue, saturation) = hueAndSaturation()
    return Color.hsl(
        hue = hue,
        saturation = min(saturation, maxSaturation),
        lightness = if (isDark) dark else light,
    )
}

/** HSL hue in degrees and saturation, the lightness is thrown away anyway. */
private fun Color.hueAndSaturation(): Pair<Float, Float> {
    val max = max(red, max(green, blue))
    val min = min(red, min(green, blue))
    val delta = max - min
    if (delta == 0f) return 0f to 0f

    val lightness = (max + min) / 2
    val saturation = delta / (1 - kotlin.math.abs(2 * lightness - 1))
    val hue = when (max) {
        red -> 60 * (((green - blue) / delta).mod(6f))
        green -> 60 * ((blue - red) / delta + 2)
        else -> 60 * ((red - green) / delta + 4)
    }
    return hue to saturation.coerceIn(0f, 1f)
}

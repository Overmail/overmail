package es.jvbabi.overmail.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A label's color as the fill behind its name, exactly the web app's tinted badge
 * (`oklch(from <color> 0.93 min(c, 0.09) h)`, 0.28 in the dark): the hue is the label's, the
 * lightness is the theme's. A label color is derived from its name and knows nothing about the
 * theme, so taken as-is it could leave a fill the text is unreadable on.
 */
@Composable
fun Color.labelContainerColor(): Color = withOklch(light = 0.93f, dark = 0.28f, maxChroma = 0.09f)

/** The same hue dark enough (or light enough) to stand as an icon on the surface itself. */
@Composable
fun Color.labelContentColor(): Color = withOklch(light = 0.55f, dark = 0.80f, maxChroma = 0.12f)

@Composable
private fun Color.withOklch(light: Float, dark: Float, maxChroma: Float): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // Oklab's a and b are the chroma and hue in cartesian form.
    val lab = convert(ColorSpaces.Oklab)
    val chroma = min(sqrt(lab.green * lab.green + lab.blue * lab.blue), maxChroma)
    val hue = atan2(lab.blue, lab.green)

    return Color(
        red = if (isDark) dark else light,
        green = chroma * cos(hue),
        blue = chroma * sin(hue),
        alpha = alpha,
        colorSpace = ColorSpaces.Oklab,
    ).convert(ColorSpaces.Srgb)
}

package es.jvbabi.overmail.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * The base colours -- red, green, orange and the rest -- for what a theme's own colours do not
 * cover: a label's colour, a warning, a state. Two layers:
 *
 * - [Tailwind] is the palette itself, every shade, exactly what the web app draws with.
 * - [BaseColors] is that palette adapted to light and dark: per colour the roles Material has for
 *   its own ones, picked from the shades so that what goes on top stays readable. Reach for these
 *   first, and for a raw shade only where none of the roles fits.
 */

/** The eleven shades of one Tailwind colour, from 50, nearly white, to 950, nearly black. */
@Immutable
data class Shades(
    val s50: Color,
    val s100: Color,
    val s200: Color,
    val s300: Color,
    val s400: Color,
    val s500: Color,
    val s600: Color,
    val s700: Color,
    val s800: Color,
    val s900: Color,
    val s950: Color,
) {
    /** The shade named like Tailwind names it, `500` for [s500]. */
    operator fun get(shade: Int): Color = when (shade) {
        50 -> s50
        100 -> s100
        200 -> s200
        300 -> s300
        400 -> s400
        500 -> s500
        600 -> s600
        700 -> s700
        800 -> s800
        900 -> s900
        950 -> s950
        else -> throw IllegalArgumentException("No shade $shade, Tailwind has 50, 100 to 900 in steps of 100, and 950")
    }
}

/**
 * Tailwind's palette as the web app has it: Tailwind 4, whose colours are defined in oklch,
 * converted to sRGB -- the few that reach beyond sRGB are cut to its edge, which is what a browser
 * on an sRGB screen shows as well. Generated from `tailwindcss/theme.css` 4.3.3.
 */
object Tailwind {
    val red = Shades(Color(0xFFFEF2F2), Color(0xFFFFE2E2), Color(0xFFFFC9C9), Color(0xFFFFA2A2), Color(0xFFFF6467), Color(0xFFFB2C36), Color(0xFFE7000B), Color(0xFFC10007), Color(0xFF9F0712), Color(0xFF82181A), Color(0xFF460809))
    val orange = Shades(Color(0xFFFFF7ED), Color(0xFFFFEDD4), Color(0xFFFFD6A7), Color(0xFFFFB86A), Color(0xFFFF8904), Color(0xFFFF6900), Color(0xFFF54900), Color(0xFFCA3500), Color(0xFF9F2D00), Color(0xFF7E2A0C), Color(0xFF441306))
    val amber = Shades(Color(0xFFFFFBEB), Color(0xFFFEF3C6), Color(0xFFFEE685), Color(0xFFFFD230), Color(0xFFFFB900), Color(0xFFFE9A00), Color(0xFFE17100), Color(0xFFBB4D00), Color(0xFF973C00), Color(0xFF7B3306), Color(0xFF461901))
    val yellow = Shades(Color(0xFFFEFCE8), Color(0xFFFEF9C2), Color(0xFFFFF085), Color(0xFFFFDF20), Color(0xFFFDC700), Color(0xFFF0B100), Color(0xFFD08700), Color(0xFFA65F00), Color(0xFF894B00), Color(0xFF733E0A), Color(0xFF432004))
    val lime = Shades(Color(0xFFF7FEE7), Color(0xFFECFCCA), Color(0xFFD8F999), Color(0xFFBBF451), Color(0xFF9AE600), Color(0xFF7CCF00), Color(0xFF5EA500), Color(0xFF497D00), Color(0xFF3C6300), Color(0xFF35530E), Color(0xFF192E03))
    val green = Shades(Color(0xFFF0FDF4), Color(0xFFDCFCE7), Color(0xFFB9F8CF), Color(0xFF7BF1A8), Color(0xFF05DF72), Color(0xFF00C950), Color(0xFF00A63E), Color(0xFF008236), Color(0xFF016630), Color(0xFF0D542B), Color(0xFF032E15))
    val emerald = Shades(Color(0xFFECFDF5), Color(0xFFD0FAE5), Color(0xFFA4F4CF), Color(0xFF5EE9B5), Color(0xFF00D492), Color(0xFF00BC7D), Color(0xFF009966), Color(0xFF007A55), Color(0xFF006045), Color(0xFF004F3B), Color(0xFF002C22))
    val teal = Shades(Color(0xFFF0FDFA), Color(0xFFCBFBF1), Color(0xFF96F7E4), Color(0xFF46ECD5), Color(0xFF00D5BE), Color(0xFF00BBA7), Color(0xFF009689), Color(0xFF00786F), Color(0xFF005F5A), Color(0xFF0B4F4A), Color(0xFF022F2E))
    val cyan = Shades(Color(0xFFECFEFF), Color(0xFFCEFAFE), Color(0xFFA2F4FD), Color(0xFF53EAFD), Color(0xFF00D3F2), Color(0xFF00B8DB), Color(0xFF0092B8), Color(0xFF007595), Color(0xFF005F78), Color(0xFF104E64), Color(0xFF053345))
    val sky = Shades(Color(0xFFF0F9FF), Color(0xFFDFF2FE), Color(0xFFB8E6FE), Color(0xFF74D4FF), Color(0xFF00BCFF), Color(0xFF00A6F4), Color(0xFF0084D1), Color(0xFF0069A8), Color(0xFF00598A), Color(0xFF024A70), Color(0xFF052F4A))
    val blue = Shades(Color(0xFFEFF6FF), Color(0xFFDBEAFE), Color(0xFFBEDBFF), Color(0xFF8EC5FF), Color(0xFF51A2FF), Color(0xFF2B7FFF), Color(0xFF155DFC), Color(0xFF1447E6), Color(0xFF193CB8), Color(0xFF1C398E), Color(0xFF162456))
    val indigo = Shades(Color(0xFFEEF2FF), Color(0xFFE0E7FF), Color(0xFFC6D2FF), Color(0xFFA3B3FF), Color(0xFF7C86FF), Color(0xFF615FFF), Color(0xFF4F39F6), Color(0xFF432DD7), Color(0xFF372AAC), Color(0xFF312C85), Color(0xFF1E1A4D))
    val violet = Shades(Color(0xFFF5F3FF), Color(0xFFEDE9FE), Color(0xFFDDD6FF), Color(0xFFC4B4FF), Color(0xFFA684FF), Color(0xFF8E51FF), Color(0xFF7F22FE), Color(0xFF7008E7), Color(0xFF5D0EC0), Color(0xFF4D179A), Color(0xFF2F0D68))
    val purple = Shades(Color(0xFFFAF5FF), Color(0xFFF3E8FF), Color(0xFFE9D4FF), Color(0xFFDAB2FF), Color(0xFFC27AFF), Color(0xFFAD46FF), Color(0xFF9810FA), Color(0xFF8200DB), Color(0xFF6E11B0), Color(0xFF59168B), Color(0xFF3C0366))
    val fuchsia = Shades(Color(0xFFFDF4FF), Color(0xFFFAE8FF), Color(0xFFF6CFFF), Color(0xFFF4A8FF), Color(0xFFED6AFF), Color(0xFFE12AFB), Color(0xFFC800DE), Color(0xFFA800B7), Color(0xFF8A0194), Color(0xFF721378), Color(0xFF4B004F))
    val pink = Shades(Color(0xFFFDF2F8), Color(0xFFFCE7F3), Color(0xFFFCCEE8), Color(0xFFFDA5D5), Color(0xFFFB64B6), Color(0xFFF6339A), Color(0xFFE60076), Color(0xFFC6005C), Color(0xFFA3004C), Color(0xFF861043), Color(0xFF510424))
    val rose = Shades(Color(0xFFFFF1F2), Color(0xFFFFE4E6), Color(0xFFFFCCD3), Color(0xFFFFA1AD), Color(0xFFFF637E), Color(0xFFFF2056), Color(0xFFEC003F), Color(0xFFC70036), Color(0xFFA50036), Color(0xFF8B0836), Color(0xFF4D0218))
    val slate = Shades(Color(0xFFF8FAFC), Color(0xFFF1F5F9), Color(0xFFE2E8F0), Color(0xFFCAD5E2), Color(0xFF90A1B9), Color(0xFF62748E), Color(0xFF45556C), Color(0xFF314158), Color(0xFF1D293D), Color(0xFF0F172B), Color(0xFF020618))
    val gray = Shades(Color(0xFFF9FAFB), Color(0xFFF3F4F6), Color(0xFFE5E7EB), Color(0xFFD1D5DC), Color(0xFF99A1AF), Color(0xFF6A7282), Color(0xFF4A5565), Color(0xFF364153), Color(0xFF1E2939), Color(0xFF101828), Color(0xFF030712))
    val zinc = Shades(Color(0xFFFAFAFA), Color(0xFFF4F4F5), Color(0xFFE4E4E7), Color(0xFFD4D4D8), Color(0xFF9F9FA9), Color(0xFF71717B), Color(0xFF52525C), Color(0xFF3F3F46), Color(0xFF27272A), Color(0xFF18181B), Color(0xFF09090B))
    val neutral = Shades(Color(0xFFFAFAFA), Color(0xFFF5F5F5), Color(0xFFE5E5E5), Color(0xFFD4D4D4), Color(0xFFA1A1A1), Color(0xFF737373), Color(0xFF525252), Color(0xFF404040), Color(0xFF262626), Color(0xFF171717), Color(0xFF0A0A0A))
    val stone = Shades(Color(0xFFFAFAF9), Color(0xFFF5F5F4), Color(0xFFE7E5E4), Color(0xFFD6D3D1), Color(0xFFA6A09B), Color(0xFF79716B), Color(0xFF57534D), Color(0xFF44403B), Color(0xFF292524), Color(0xFF1C1917), Color(0xFF0C0A09))
    val mauve = Shades(Color(0xFFFAFAFA), Color(0xFFF3F1F3), Color(0xFFE7E4E7), Color(0xFFD7D0D7), Color(0xFFA89EA9), Color(0xFF79697B), Color(0xFF594C5B), Color(0xFF463947), Color(0xFF2A212C), Color(0xFF1D161E), Color(0xFF0C090C))
    val olive = Shades(Color(0xFFFBFBF9), Color(0xFFF4F4F0), Color(0xFFE8E8E3), Color(0xFFD8D8D0), Color(0xFFABAB9C), Color(0xFF7C7C67), Color(0xFF5B5B4B), Color(0xFF474739), Color(0xFF2B2B22), Color(0xFF1D1D16), Color(0xFF0C0C09))
    val mist = Shades(Color(0xFFF9FBFB), Color(0xFFF1F3F3), Color(0xFFE3E7E8), Color(0xFFD0D6D8), Color(0xFF9CA8AB), Color(0xFF67787C), Color(0xFF4B585B), Color(0xFF394447), Color(0xFF22292B), Color(0xFF161B1D), Color(0xFF090B0C))
    val taupe = Shades(Color(0xFFFBFAF9), Color(0xFFF3F1F1), Color(0xFFE8E4E3), Color(0xFFD8D2D0), Color(0xFFABA09C), Color(0xFF7C6D67), Color(0xFF5B4F4B), Color(0xFF473C39), Color(0xFF2B2422), Color(0xFF1D1816), Color(0xFF0C0A09))
}

/** One base colour, adapted to light or dark: the roles Material has for its own colours. */
@Immutable
data class BaseColor(
    /** Filled and loud: a button, a dot, an icon standing on its own. */
    val color: Color,
    /** Text and icons on [color]. */
    val onColor: Color,
    /** Quiet: the background of a badge, a chip, a banner. */
    val container: Color,
    /** Text and icons on [container]. */
    val onContainer: Color,
    /** A border in this colour, on the background or around [container]. */
    val outline: Color,
    /** Every shade, for what none of the roles covers. */
    val shades: Shades,
)

/** Every base colour, adapted to one theme; see [MaterialTheme.baseColors]. */
@Immutable
data class BaseColors(
    val red: BaseColor,
    val orange: BaseColor,
    val amber: BaseColor,
    val yellow: BaseColor,
    val lime: BaseColor,
    val green: BaseColor,
    val emerald: BaseColor,
    val teal: BaseColor,
    val cyan: BaseColor,
    val sky: BaseColor,
    val blue: BaseColor,
    val indigo: BaseColor,
    val violet: BaseColor,
    val purple: BaseColor,
    val fuchsia: BaseColor,
    val pink: BaseColor,
    val rose: BaseColor,
    val slate: BaseColor,
    val gray: BaseColor,
    val zinc: BaseColor,
    val neutral: BaseColor,
    val stone: BaseColor,
    val mauve: BaseColor,
    val olive: BaseColor,
    val mist: BaseColor,
    val taupe: BaseColor,
)

/**
 * [color] is the lightest shade from 600 on that white text on it reaches 4.5:1 (WCAG AA): the
 * yellows and greens are too light at 600 and take 700.
 */
private fun Shades.light(color: Color = s600) = BaseColor(
    color = color,
    onColor = Color.White,
    container = s100,
    onContainer = s800,
    outline = s300,
    shades = this,
)

/** 400 with text in 950 on it reaches 4.5:1 for every colour, so dark needs no exceptions. */
private fun Shades.dark() = BaseColor(
    color = s400,
    onColor = s950,
    container = s950,
    onContainer = s200,
    outline = s800,
    shades = this,
)

val lightBaseColors = BaseColors(
    red = Tailwind.red.light(),
    orange = Tailwind.orange.light(color = Tailwind.orange.s700),
    amber = Tailwind.amber.light(color = Tailwind.amber.s700),
    yellow = Tailwind.yellow.light(color = Tailwind.yellow.s700),
    lime = Tailwind.lime.light(color = Tailwind.lime.s700),
    green = Tailwind.green.light(color = Tailwind.green.s700),
    emerald = Tailwind.emerald.light(color = Tailwind.emerald.s700),
    teal = Tailwind.teal.light(color = Tailwind.teal.s700),
    cyan = Tailwind.cyan.light(color = Tailwind.cyan.s700),
    sky = Tailwind.sky.light(color = Tailwind.sky.s700),
    blue = Tailwind.blue.light(),
    indigo = Tailwind.indigo.light(),
    violet = Tailwind.violet.light(),
    purple = Tailwind.purple.light(),
    fuchsia = Tailwind.fuchsia.light(),
    pink = Tailwind.pink.light(),
    rose = Tailwind.rose.light(),
    slate = Tailwind.slate.light(),
    gray = Tailwind.gray.light(),
    zinc = Tailwind.zinc.light(),
    neutral = Tailwind.neutral.light(),
    stone = Tailwind.stone.light(),
    mauve = Tailwind.mauve.light(),
    olive = Tailwind.olive.light(),
    mist = Tailwind.mist.light(),
    taupe = Tailwind.taupe.light(),
)

val darkBaseColors = BaseColors(
    red = Tailwind.red.dark(),
    orange = Tailwind.orange.dark(),
    amber = Tailwind.amber.dark(),
    yellow = Tailwind.yellow.dark(),
    lime = Tailwind.lime.dark(),
    green = Tailwind.green.dark(),
    emerald = Tailwind.emerald.dark(),
    teal = Tailwind.teal.dark(),
    cyan = Tailwind.cyan.dark(),
    sky = Tailwind.sky.dark(),
    blue = Tailwind.blue.dark(),
    indigo = Tailwind.indigo.dark(),
    violet = Tailwind.violet.dark(),
    purple = Tailwind.purple.dark(),
    fuchsia = Tailwind.fuchsia.dark(),
    pink = Tailwind.pink.dark(),
    rose = Tailwind.rose.dark(),
    slate = Tailwind.slate.dark(),
    gray = Tailwind.gray.dark(),
    zinc = Tailwind.zinc.dark(),
    neutral = Tailwind.neutral.dark(),
    stone = Tailwind.stone.dark(),
    mauve = Tailwind.mauve.dark(),
    olive = Tailwind.olive.dark(),
    mist = Tailwind.mist.dark(),
    taupe = Tailwind.taupe.dark(),
)

val LocalBaseColors = staticCompositionLocalOf { lightBaseColors }

/** The base colours of the current theme, light or dark as [AppTheme] was told. */
val MaterialTheme.baseColors: BaseColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBaseColors.current

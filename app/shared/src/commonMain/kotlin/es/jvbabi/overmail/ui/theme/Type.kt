@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import overmail.app.shared.generated.resources.*

/** Inter: everything the app writes, unless it says otherwise. */
@Composable
fun bodyFontFamily() = FontFamily(
    Font(Res.font.inter_thin, FontWeight.Thin, FontStyle.Normal),
    Font(Res.font.inter_thin_italic, FontWeight.Thin, FontStyle.Italic),
    Font(Res.font.inter_extralight, FontWeight.ExtraLight, FontStyle.Normal),
    Font(Res.font.inter_extralight_italic, FontWeight.ExtraLight, FontStyle.Italic),
    Font(Res.font.inter_light, FontWeight.Light, FontStyle.Normal),
    Font(Res.font.inter_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(Res.font.inter_regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.inter_regular_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.inter_medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.inter_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(Res.font.inter_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.inter_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
    Font(Res.font.inter_bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.inter_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(Res.font.inter_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(Res.font.inter_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(Res.font.inter_black, FontWeight.Black, FontStyle.Normal),
    Font(Res.font.inter_black_italic, FontWeight.Black, FontStyle.Italic),
)

/** Playfair Display: for headlines that introduce something, like the onboarding's. Set per text. */
@Composable
fun displayFontFamily() = FontFamily(
    Font(Res.font.PlayfairDisplay_Regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_Italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.PlayfairDisplay_Medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_MediumItalic, FontWeight.Medium, FontStyle.Italic),
    Font(Res.font.PlayfairDisplay_SemiBold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_SemiBoldItalic, FontWeight.SemiBold, FontStyle.Italic),
    Font(Res.font.PlayfairDisplay_Bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_BoldItalic, FontWeight.Bold, FontStyle.Italic),
    Font(Res.font.PlayfairDisplay_ExtraBold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_ExtraBoldItalic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(Res.font.PlayfairDisplay_Black, FontWeight.Black, FontStyle.Normal),
    Font(Res.font.PlayfairDisplay_BlackItalic, FontWeight.Black, FontStyle.Italic),
)

/** JetBrains Mono: codes, addresses, raw mail source. Set per text. */
@Composable
fun monospaceFontFamily() = FontFamily(
    Font(Res.font.jetbrains_mono_thin, FontWeight.Thin, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_thin_italic, FontWeight.Thin, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_extralight, FontWeight.ExtraLight, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_extralight_italic, FontWeight.ExtraLight, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_light, FontWeight.Light, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(Res.font.jetbrains_mono_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(Res.font.jetbrains_mono_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
)

private val baseline = Typography()

/**
 * Material's scale with every style set in Inter, the emphasized ones included -- left out, those
 * would stay in the platform font.
 */
@Composable
fun appTypography(): Typography {
    val body = bodyFontFamily()
    return Typography(
        displayLarge = baseline.displayLarge.copy(fontFamily = body),
        displayMedium = baseline.displayMedium.copy(fontFamily = body),
        displaySmall = baseline.displaySmall.copy(fontFamily = body),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = body),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = body),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = body),
        titleLarge = baseline.titleLarge.copy(fontFamily = body),
        titleMedium = baseline.titleMedium.copy(fontFamily = body),
        titleSmall = baseline.titleSmall.copy(fontFamily = body),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = body),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = body),
        bodySmall = baseline.bodySmall.copy(fontFamily = body),
        labelLarge = baseline.labelLarge.copy(fontFamily = body),
        labelMedium = baseline.labelMedium.copy(fontFamily = body),
        labelSmall = baseline.labelSmall.copy(fontFamily = body),
        displayLargeEmphasized = baseline.displayLargeEmphasized.copy(fontFamily = body),
        displayMediumEmphasized = baseline.displayMediumEmphasized.copy(fontFamily = body),
        displaySmallEmphasized = baseline.displaySmallEmphasized.copy(fontFamily = body),
        headlineLargeEmphasized = baseline.headlineLargeEmphasized.copy(fontFamily = body),
        headlineMediumEmphasized = baseline.headlineMediumEmphasized.copy(fontFamily = body),
        headlineSmallEmphasized = baseline.headlineSmallEmphasized.copy(fontFamily = body),
        titleLargeEmphasized = baseline.titleLargeEmphasized.copy(fontFamily = body),
        titleMediumEmphasized = baseline.titleMediumEmphasized.copy(fontFamily = body),
        titleSmallEmphasized = baseline.titleSmallEmphasized.copy(fontFamily = body),
        bodyLargeEmphasized = baseline.bodyLargeEmphasized.copy(fontFamily = body),
        bodyMediumEmphasized = baseline.bodyMediumEmphasized.copy(fontFamily = body),
        bodySmallEmphasized = baseline.bodySmallEmphasized.copy(fontFamily = body),
        labelLargeEmphasized = baseline.labelLargeEmphasized.copy(fontFamily = body),
        labelMediumEmphasized = baseline.labelMediumEmphasized.copy(fontFamily = body),
        labelSmallEmphasized = baseline.labelSmallEmphasized.copy(fontFamily = body),
    )
}

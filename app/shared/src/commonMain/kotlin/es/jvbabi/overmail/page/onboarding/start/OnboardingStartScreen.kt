@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.page.onboarding.start

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowRight
import es.jvbabi.overmail.BuildKonfig
import es.jvbabi.overmail.page.onboarding.OnboardingScreens
import es.jvbabi.overmail.ui.components.Button
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.displayFontFamily
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.app_icon
import overmail.app.shared.generated.resources.onboarding_welcome_continue
import overmail.app.shared.generated.resources.onboarding_welcome_subtitle
import overmail.app.shared.generated.resources.onboarding_welcome_title

@Composable
fun OnboardingStartScreen(
    backstack: OnboardingScreens.Backstack,
) {
    OnboardingStartContent(
        onContinue = remember { { backstack.add(OnboardingScreens.Auth) } }
    )
}

@Composable
private fun OnboardingStartContent(
    onContinue: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = BuildKonfig.CURRENT_VERSION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(safeDrawing)
                .padding(16.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(64.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(127.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(Res.string.onboarding_welcome_title),
                fontFamily = displayFontFamily(),
                style = MaterialTheme.typography.displaySmallEmphasized,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(Res.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f),
                textAlign = TextAlign.Center,
            )
        }

        Button(
            text = stringResource(Res.string.onboarding_welcome_continue),
            icon = PhIcons.Regular.ArrowRight,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp)
                .padding(bottom = safeDrawing.calculateBottomPadding() + 16.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onContinue()
            },
        )
    }
}

@Composable
@Preview
private fun OnboardingStartContentPreview() {
    AppTheme {
        OnboardingStartContent(
            onContinue = {},
        )
    }
}

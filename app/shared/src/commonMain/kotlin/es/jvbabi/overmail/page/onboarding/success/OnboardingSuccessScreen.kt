@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package es.jvbabi.overmail.page.onboarding.success

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowRight
import com.phosphor.icons.regular.Check
import es.jvbabi.overmail.ui.components.Button
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.displayFontFamily
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.onboarding_success_continue
import overmail.app.shared.generated.resources.onboarding_success_subtitle
import overmail.app.shared.generated.resources.onboarding_success_title

@Composable
fun OnboardingSuccessScreen(
    viewModel: OnboardingSuccessViewModel,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnboardingSuccessContent(
        state = state,
        contentPadding = contentPadding,
        onDone = onDone,
    )
}

@Composable
private fun OnboardingSuccessContent(
    state: OnboardingSuccessState,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    val badgeScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        badgeScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .scale(badgeScale.value)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            ) {
                Icon(
                    imageVector = PhIcons.Regular.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
            Spacer(Modifier.size(32.dp))

            // Waits for the name rather than greeting nobody and then correcting itself.
            AnimatedVisibility(
                visible = state.name != null,
                enter = fadeIn(tween(400, delayMillis = 200)) +
                    slideInVertically(tween(400, delayMillis = 200)) { it / 4 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.onboarding_success_title, state.name.orEmpty()),
                        fontFamily = displayFontFamily(),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(Res.string.onboarding_success_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Button(
            text = stringResource(Res.string.onboarding_success_continue),
            icon = PhIcons.Regular.ArrowRight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onDone()
            },
        )
    }
}

@Composable
@Preview
private fun OnboardingSuccessPreview() {
    AppTheme {
        OnboardingSuccessContent(
            state = OnboardingSuccessState(name = "Julius"),
            contentPadding = PaddingValues(),
            onDone = {},
        )
    }
}

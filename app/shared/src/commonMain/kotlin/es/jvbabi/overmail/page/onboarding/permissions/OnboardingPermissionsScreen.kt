package es.jvbabi.overmail.page.onboarding.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.ArrowRight
import es.jvbabi.overmail.page.onboarding.components.OnboardingHeader
import es.jvbabi.overmail.ui.components.Button
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.onboarding_permissions_continue
import overmail.app.shared.generated.resources.onboarding_permissions_subtitle
import overmail.app.shared.generated.resources.onboarding_permissions_title

@Composable
fun OnboardingPermissionsScreen(
    viewModel: OnboardingPermissionsViewModel,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isDone) {
        if (state.isDone) onDone()
    }

    OnboardingPermissionsContent(
        contentPadding = contentPadding,
        onEvent = viewModel::onEvent,
    )
}

@Composable
private fun OnboardingPermissionsContent(
    contentPadding: PaddingValues,
    onEvent: (OnboardingPermissionsEvent) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
    ) {
        OnboardingHeader(
            title = stringResource(Res.string.onboarding_permissions_title),
            subtitle = stringResource(Res.string.onboarding_permissions_subtitle),
        )

        Column(
            modifier = Modifier
                .weight(1f, true)
                .fillMaxWidth()
        ) {}

        Button(
            text = stringResource(Res.string.onboarding_permissions_continue),
            icon = PhIcons.Regular.ArrowRight,
            modifier = Modifier.padding(bottom = 16.dp),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onEvent(OnboardingPermissionsEvent.Request)
            },
        )
    }
}

@Composable
@Preview
private fun OnboardingPermissionsPreview() {
    AppTheme {
        OnboardingPermissionsContent(
            contentPadding = PaddingValues(),
            onEvent = {},
        )
    }
}

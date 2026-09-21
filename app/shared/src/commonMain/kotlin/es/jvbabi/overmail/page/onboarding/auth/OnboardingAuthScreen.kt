package es.jvbabi.overmail.page.onboarding.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.jvbabi.overmail.page.onboarding.components.OnboardingHeader
import es.jvbabi.overmail.ui.components.Button
import es.jvbabi.overmail.ui.components.ButtonState
import es.jvbabi.overmail.ui.components.ButtonType
import es.jvbabi.overmail.ui.components.QrScanner
import es.jvbabi.overmail.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.move_right
import overmail.app.shared.generated.resources.onboarding_auth_code_placeholder
import overmail.app.shared.generated.resources.onboarding_auth_code_subtitle
import overmail.app.shared.generated.resources.onboarding_auth_code_title
import overmail.app.shared.generated.resources.onboarding_auth_error_bad_format
import overmail.app.shared.generated.resources.onboarding_auth_error_not_exists
import overmail.app.shared.generated.resources.onboarding_auth_error_other
import overmail.app.shared.generated.resources.onboarding_auth_qr_subtitle
import overmail.app.shared.generated.resources.onboarding_auth_qr_title
import overmail.app.shared.generated.resources.onboarding_auth_submit
import overmail.app.shared.generated.resources.onboarding_auth_use_code
import overmail.app.shared.generated.resources.onboarding_auth_use_qr

@Composable
fun OnboardingAuthScreen(
    viewModel: OnboardingAuthViewModel,
    contentPadding: PaddingValues,
) {
    LaunchedEffect(Unit) {
        viewModel.onScreenUp()
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    OnboardingAuthContent(
        state = state,
        contentPadding = contentPadding,
        onEvent = viewModel::onEvent,
    )
}

@Composable
private fun OnboardingAuthContent(
    state: OnboardingAuthState,
    contentPadding: PaddingValues,
    onEvent: (OnboardingAuthEvent) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val isProcessing = state.codeState is OnboardingAuthState.CodeState.Processing

    LaunchedEffect(state.codeState) {
        if (state.codeState is OnboardingAuthState.CodeState.Error) haptic.performHapticFeedback(HapticFeedbackType.Reject)
    }

    var code by rememberSaveable { mutableStateOf(state.code.orEmpty()) }
    val submit = {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        focusManager.clearFocus()
        onEvent(OnboardingAuthEvent.SubmitCode(code))
    }

    // One transition for header, input and buttons, so the three parts switch in step.
    val modeTransition = updateTransition(targetState = state.mode, label = "mode")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
    ) {
        modeTransition.AnimatedContent(transitionSpec = { modeSwitch }) { mode ->
            when (mode) {
                OnboardingAuthState.Mode.Qr -> OnboardingHeader(
                    title = stringResource(Res.string.onboarding_auth_qr_title),
                    subtitle = stringResource(Res.string.onboarding_auth_qr_subtitle),
                )
                OnboardingAuthState.Mode.Code -> OnboardingHeader(
                    title = stringResource(Res.string.onboarding_auth_code_title),
                    subtitle = stringResource(Res.string.onboarding_auth_code_subtitle),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f, true)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            modeTransition.AnimatedContent(
                transitionSpec = { modeSwitch },
                contentAlignment = Alignment.TopCenter,
            ) { mode ->
                when (mode) {
                    OnboardingAuthState.Mode.Qr -> Box(
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (state.isCameraPermissionGranted) QrScanner(
                            onQrCodeScanned = { onEvent(OnboardingAuthEvent.SubmitCode(it)) },
                            modifier = Modifier.matchParentSize(),
                        )
                        if (isProcessing) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }

                    OnboardingAuthState.Mode.Code -> OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = { Text(stringResource(Res.string.onboarding_auth_code_placeholder)) },
                        enabled = !isProcessing,
                        singleLine = true,
                        isError = state.codeState is OnboardingAuthState.CodeState.Error,
                        shape = RoundedCornerShape(8.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            CodeError(
                codeState = state.codeState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        modeTransition.AnimatedContent(
            transitionSpec = { modeSwitch },
            contentAlignment = Alignment.BottomCenter,
        ) { mode ->
            when (mode) {
                OnboardingAuthState.Mode.Qr -> Button(
                    text = stringResource(Res.string.onboarding_auth_use_code),
                    type = ButtonType.Outlined,
                    modifier = Modifier.padding(bottom = 16.dp),
                    onClick = { onEvent(OnboardingAuthEvent.SetMode(OnboardingAuthState.Mode.Code)) },
                )

                OnboardingAuthState.Mode.Code -> Column {
                    Button(
                        text = stringResource(Res.string.onboarding_auth_submit),
                        icon = Res.drawable.move_right,
                        state = when {
                            isProcessing -> ButtonState.Loading
                            code.isBlank() -> ButtonState.Disabled
                            else -> ButtonState.Enabled
                        },
                        onClick = submit,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        text = stringResource(Res.string.onboarding_auth_use_qr),
                        type = ButtonType.Outlined,
                        state = if (isProcessing) ButtonState.Disabled else ButtonState.Enabled,
                        modifier = Modifier.padding(bottom = 16.dp),
                        onClick = { onEvent(OnboardingAuthEvent.SetMode(OnboardingAuthState.Mode.Qr)) },
                    )
                }
            }
        }
    }
}

/**
 * Fade-through between QR and manual entry: the old part fades out quickly, the new one fades
 * and grows in after it, and the space between them resizes rather than jumping.
 */
private val modeSwitch = ContentTransform(
    targetContentEnter = fadeIn(tween(durationMillis = 220, delayMillis = 90)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 220, delayMillis = 90)),
    initialContentExit = fadeOut(tween(durationMillis = 90)),
    sizeTransform = SizeTransform(clip = false),
)

@Composable
private fun CodeError(
    codeState: OnboardingAuthState.CodeState,
    modifier: Modifier = Modifier,
) {
    val message = when (codeState) {
        is OnboardingAuthState.CodeState.Error.BadFormat -> stringResource(Res.string.onboarding_auth_error_bad_format)
        is OnboardingAuthState.CodeState.Error.NotExists -> stringResource(Res.string.onboarding_auth_error_not_exists)
        is OnboardingAuthState.CodeState.Error.OtherError -> stringResource(Res.string.onboarding_auth_error_other, codeState.message)
        else -> return
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
@Preview
private fun OnboardingAuthScreenQrPreview() {
    AppTheme {
        OnboardingAuthContent(
            state = OnboardingAuthState(mode = OnboardingAuthState.Mode.Qr),
            contentPadding = PaddingValues(),
            onEvent = {},
        )
    }
}

@Composable
@Preview
private fun OnboardingAuthScreenCodePreview() {
    AppTheme {
        OnboardingAuthContent(
            state = OnboardingAuthState(
                mode = OnboardingAuthState.Mode.Code,
                code = "overmail://overmail.example/auth?code=abc",
                codeState = OnboardingAuthState.CodeState.Error.NotExists,
            ),
            contentPadding = PaddingValues(),
            onEvent = {},
        )
    }
}

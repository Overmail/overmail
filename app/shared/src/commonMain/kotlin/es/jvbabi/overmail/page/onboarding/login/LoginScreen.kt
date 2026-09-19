package es.jvbabi.overmail.page.onboarding.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ui.components.qr.CameraPermissionGate
import es.jvbabi.overmail.ui.components.qr.QrScanner
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.common_back
import overmail.app.shared.generated.resources.onboarding_login_code_invalid
import overmail.app.shared.generated.resources.onboarding_login_code_label
import overmail.app.shared.generated.resources.onboarding_login_code_message
import overmail.app.shared.generated.resources.onboarding_login_code_submit
import overmail.app.shared.generated.resources.onboarding_login_error_expired_code
import overmail.app.shared.generated.resources.onboarding_login_error_failed
import overmail.app.shared.generated.resources.onboarding_login_error_unknown_code
import overmail.app.shared.generated.resources.onboarding_login_error_unreachable
import overmail.app.shared.generated.resources.onboarding_login_mode_enter_code
import overmail.app.shared.generated.resources.onboarding_login_mode_scan
import overmail.app.shared.generated.resources.onboarding_login_scan_invalid
import overmail.app.shared.generated.resources.onboarding_login_scan_message
import overmail.app.shared.generated.resources.onboarding_login_signing_in
import overmail.app.shared.generated.resources.onboarding_login_title

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state = viewModel.state

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.onboarding_login_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(
                when (state.mode) {
                    LoginMode.Scan -> Res.string.onboarding_login_scan_message
                    LoginMode.EnterCode -> Res.string.onboarding_login_code_message
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val errorMessage = state.error?.let { loginErrorMessage(it, state.mode) }

            when {
                // Replaces the scanner rather than covering it: a camera that keeps reading the
                // same code while it is being redeemed has nothing left to report. It stays up
                // once signed in too, so the scanner does not flash back for the frame or two
                // before `AppViewModel` leaves the onboarding.
                state.isRedeeming || state.isSignedIn -> Redeeming()

                state.mode == LoginMode.Scan -> ScanCode(
                    errorMessage = errorMessage,
                    onScan = viewModel::onScan,
                )

                else -> EnterCode(
                    value = state.codeInput,
                    errorMessage = errorMessage,
                    onValueChange = viewModel::onCodeInputChange,
                    onSubmit = viewModel::submitCodeInput,
                )
            }
        }

        SingleChoiceSegmentedButtonRow {
            LoginMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    enabled = !state.isRedeeming,
                    shape = SegmentedButtonDefaults.itemShape(index, LoginMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                LoginMode.Scan -> Res.string.onboarding_login_mode_scan
                                LoginMode.EnterCode -> Res.string.onboarding_login_mode_enter_code
                            }
                        )
                    )
                }
            }
        }

        TextButton(onClick = onBack, enabled = !state.isRedeeming) {
            Text(stringResource(Res.string.common_back))
        }
    }
}

@Composable
private fun Redeeming() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(Res.string.onboarding_login_signing_in),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ScanCode(
    errorMessage: String?,
    onScan: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CameraPermissionGate(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            QrScanner(
                onScan = onScan,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp)),
            )
        }
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EnterCode(
    value: String,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.onboarding_login_code_label)) },
            isError = errorMessage != null,
            supportingText = if (errorMessage != null) {
                { Text(errorMessage) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
        ) {
            Text(stringResource(Res.string.onboarding_login_code_submit))
        }
    }
}

/**
 * One message per [LoginError]. The format error is the only one that depends on the mode -- a
 * scan and a typed code fail at it for different reasons.
 */
@Composable
private fun loginErrorMessage(error: LoginError, mode: LoginMode): String = stringResource(
    when (error) {
        LoginError.InvalidFormat -> when (mode) {
            LoginMode.Scan -> Res.string.onboarding_login_scan_invalid
            LoginMode.EnterCode -> Res.string.onboarding_login_code_invalid
        }

        LoginError.UnknownCode -> Res.string.onboarding_login_error_unknown_code
        LoginError.ExpiredCode -> Res.string.onboarding_login_error_expired_code
        LoginError.ServerUnreachable -> Res.string.onboarding_login_error_unreachable
        LoginError.Failed -> Res.string.onboarding_login_error_failed
    }
)

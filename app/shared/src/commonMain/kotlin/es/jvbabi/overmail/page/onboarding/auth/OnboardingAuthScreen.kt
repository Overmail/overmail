package es.jvbabi.overmail.page.onboarding.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.jvbabi.overmail.ui.components.QrScanner

@Composable
fun OnboardingAuthScreen(
    viewModel: OnboardingAuthViewModel,
) {
    LaunchedEffect(Unit) {
        viewModel.onScreenUp()
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    OnboardingAuthContent(
        state = state,
        onEvent = viewModel::onEvent,
    )
}

@Composable
private fun OnboardingAuthContent(
    state: OnboardingAuthState,
    onEvent: (OnboardingAuthEvent) -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (state.mode) {
                OnboardingAuthState.Mode.Qr -> Column {
                    Text("Verwende den QR aus Overmail im Web: Einstellungen -> Geraete")

                    Box(Modifier.size(100.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (state.isCameraPermissionGranted) QrScanner(
                            onQrCodeScanned = { onEvent(OnboardingAuthEvent.SubmitCode(it)) },
                            modifier = Modifier.matchParentSize(),
                        )
                    }

                    Button(
                        onClick = { onEvent(OnboardingAuthEvent.SetMode(OnboardingAuthState.Mode.Code)) },
                    ) {
                        Text("Stattdessen Code eingeben")
                    }
                }

                OnboardingAuthState.Mode.Code -> Column {
                    Text("Gib den code ein")

                    TextField(
                        value = "",
                        onValueChange = {},
                        placeholder = { Text("overmail://...") },
                    )

                    Button(
                        onClick = { onEvent(OnboardingAuthEvent.SetMode(OnboardingAuthState.Mode.Qr)) }
                    ) {
                        Text("Stattdessen Code scannen")
                    }
                }
            }
        }
    }
}

@Composable
@Preview
private fun OnboardingAuthScreenPreview() {
    OnboardingAuthContent(
        state = OnboardingAuthState(mode = OnboardingAuthState.Mode.Qr),
        onEvent = {},
    )
}
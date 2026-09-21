package es.jvbabi.overmail.page.onboarding.start

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import es.jvbabi.overmail.page.onboarding.OnboardingScreens

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
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            Text("Willkommen bei Overmail!")
            Text("UI Design kommt noch (trust).")
            Button(
                onClick = onContinue,
            ) {
                Text("Anmelden")
            }
        }
    }
}

@Composable
@Preview
private fun OnboardingStartContentPreview() {
    OnboardingStartContent(
        onContinue = {},
    )
}
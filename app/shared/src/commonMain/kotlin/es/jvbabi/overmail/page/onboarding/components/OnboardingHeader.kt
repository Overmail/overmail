package es.jvbabi.overmail.page.onboarding.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.ui.theme.displayFontFamily

/** Title and explanation at the top of every onboarding step after the welcome screen. */
@Composable
fun OnboardingHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
) {
    Column(modifier) {
        Text(
            text = title,
            fontFamily = displayFontFamily(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle != null) Text(
            text = subtitle,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
        )
    }
}

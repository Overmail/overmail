package es.jvbabi.overmail.page.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.jvbabi.overmail.BuildKonfig
import org.koin.compose.viewmodel.koinViewModel

/**
 * Placeholder for the app's first screen. It reports whether [BuildKonfig.SERVER_URL] answers, so a
 * fresh checkout can be shown to talk to the server before there is anything to show.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Overmail",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = when (viewModel.serverState) {
                ServerState.Checking -> "Checking ${BuildKonfig.SERVER_URL}…"
                ServerState.Reachable -> "${BuildKonfig.SERVER_URL} is up"
                ServerState.Unreachable -> "${BuildKonfig.SERVER_URL} is not answering"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = viewModel::checkServer) {
            Text("Check again")
        }
        TextButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}

package es.jvbabi.overmail.page.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.data.remote.OvermailApi
import kotlinx.coroutines.launch

class HomeViewModel(
    private val api: OvermailApi,
) : ViewModel() {

    var serverState by mutableStateOf(ServerState.Checking)
        private set

    init {
        checkServer()
    }

    fun checkServer() {
        serverState = ServerState.Checking
        viewModelScope.launch {
            serverState = if (api.isHealthy()) ServerState.Reachable else ServerState.Unreachable
        }
    }
}

enum class ServerState {
    Checking,
    Reachable,
    Unreachable,
}

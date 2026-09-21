package es.jvbabi.overmail.page.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class HomeViewModel: ViewModel() {

    var serverState by mutableStateOf(ServerState.Checking)
        private set

    init {
        checkServer()
    }

    fun checkServer() {
        serverState = ServerState.Checking
        viewModelScope.launch {
        }
    }
}

enum class ServerState {
    Checking,
    Reachable,
    Unreachable,
}

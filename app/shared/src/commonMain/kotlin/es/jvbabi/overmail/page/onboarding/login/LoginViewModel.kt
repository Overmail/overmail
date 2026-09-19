package es.jvbabi.overmail.page.onboarding.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.jvbabi.overmail.domain.model.LoginCode
import es.jvbabi.overmail.domain.model.LoginResult
import es.jvbabi.overmail.domain.repository.OvermailAccountRepository
import kotlinx.coroutines.launch

class LoginViewModel(
    private val accountRepository: OvermailAccountRepository,
) : ViewModel() {

    var state by mutableStateOf(LoginState())
        private set

    fun setMode(mode: LoginMode) {
        if (state.isRedeeming) return
        state = state.copy(mode = mode, error = null)
    }

    /** Called for every frame a QR code is in view, so the same content arrives many times. */
    fun onScan(value: String) {
        if (state.loginCode != null) return
        val code = LoginCode.parse(value)
        if (code == null) {
            state = state.copy(error = LoginError.InvalidFormat)
            return
        }
        onLoginCode(code)
    }

    fun onCodeInputChange(value: String) {
        state = state.copy(codeInput = value, error = null)
    }

    fun submitCodeInput() {
        val code = LoginCode.parse(state.codeInput)
        if (code == null) {
            state = state.copy(error = LoginError.InvalidFormat)
            return
        }
        onLoginCode(code)
    }

    /**
     * Hands the code to the server and, if it holds up, is the last thing this screen does: the
     * stored account is what `AppViewModel` leaves the onboarding on.
     *
     * A failed attempt clears [LoginState.loginCode] so the scanner starts looking again -- but
     * the code itself is spent, so what it finds has to be a freshly generated one.
     */
    private fun onLoginCode(code: LoginCode) {
        if (state.isRedeeming) return
        state = state.copy(loginCode = code, error = null, isRedeeming = true)

        viewModelScope.launch {
            val result = accountRepository.redeemLoginCode(code)
            state = when (result) {
                is LoginResult.Success -> state.copy(isRedeeming = false, isSignedIn = true)
                LoginResult.UnknownCode -> state.copy(isRedeeming = false, loginCode = null, error = LoginError.UnknownCode)
                LoginResult.ExpiredCode -> state.copy(isRedeeming = false, loginCode = null, error = LoginError.ExpiredCode)
                LoginResult.ServerUnreachable -> state.copy(isRedeeming = false, loginCode = null, error = LoginError.ServerUnreachable)
                LoginResult.Failed -> state.copy(isRedeeming = false, loginCode = null, error = LoginError.Failed)
            }
        }
    }
}

data class LoginState(
    val mode: LoginMode = LoginMode.Scan,
    val codeInput: String = "",
    val error: LoginError? = null,
    val loginCode: LoginCode? = null,
    /** The code is with the server. Nothing on this screen accepts input while it is. */
    val isRedeeming: Boolean = false,
    /** The account is stored; the app leaves the onboarding on its own from here. */
    val isSignedIn: Boolean = false,
)

/** Why the screen is still here. One message each, see `LoginScreen`. */
enum class LoginError {
    /** Not an Overmail sign-in code at all -- nothing was sent anywhere. */
    InvalidFormat,
    UnknownCode,
    ExpiredCode,
    ServerUnreachable,
    Failed,
}

enum class LoginMode {
    Scan,
    EnterCode,
}

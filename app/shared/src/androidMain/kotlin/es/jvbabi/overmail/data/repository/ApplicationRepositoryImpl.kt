package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.domain.repository.ApplicationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class ApplicationRepositoryImpl(
    private val isVisibleStateFlow: StateFlow<Boolean>,
    // Passed in from the app module, which is where BuildConfig.DEBUG lives.
    override val isDebugBuild: Boolean,
) : ApplicationRepository {

    companion object {
        const val KOIN_KEY_APP_IN_FOREGROUND_FLOW = "app_in_foreground_flow"
    }

    // Read on every access rather than cached, so a language change at runtime is picked up.
    override val language: String
        get() = Locale.getDefault().language.lowercase()

    override fun getApplicationForegroundState(): Flow<Boolean> {
        return isVisibleStateFlow
    }
}
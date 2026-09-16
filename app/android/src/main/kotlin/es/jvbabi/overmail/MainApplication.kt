package es.jvbabi.overmail

import android.app.Application
import android.util.Log
import es.jvbabi.overmail.MainActivity.Companion.isVisible
import es.jvbabi.overmail.data.repository.ApplicationRepositoryImpl
import es.jvbabi.overmail.di.initKoin
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.qualifier.named
import org.koin.dsl.module

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // An exception on a background thread otherwise leaves the app running in a state nobody
        // designed for. Crashing is the honest outcome, and it shows up in the crash log.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Overmail", "Uncaught exception on thread: ${thread.name}", throwable)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        initKoin {
            androidContext(this@MainApplication)
            androidLogger()

            modules(module {
                single<ApplicationRepository> {
                    ApplicationRepositoryImpl(
                        isVisibleStateFlow = get(named(ApplicationRepositoryImpl.KOIN_KEY_APP_IN_FOREGROUND_FLOW)),
                        isDebugBuild = BuildConfig.DEBUG,
                    )
                }
                single<StateFlow<Boolean>>(named(ApplicationRepositoryImpl.KOIN_KEY_APP_IN_FOREGROUND_FLOW)) { isVisible.asStateFlow() }
            })
        }
    }
}

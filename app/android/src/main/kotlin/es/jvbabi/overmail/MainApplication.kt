package es.jvbabi.overmail

import android.app.Application
import android.util.Log
import es.jvbabi.overmail.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

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
        }
    }
}

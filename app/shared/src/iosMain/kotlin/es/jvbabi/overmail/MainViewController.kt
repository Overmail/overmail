package es.jvbabi.overmail

import androidx.compose.ui.window.ComposeUIViewController
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.ios.PermissionsController as IosPermissionsController
import es.jvbabi.overmail.data.push.ApplePush
import es.jvbabi.overmail.data.repository.IosApplicationRepository
import es.jvbabi.overmail.di.initKoin
import es.jvbabi.overmail.domain.intent.AppIntents
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import org.koin.dsl.module
import platform.UIKit.UIViewController

/** Entry point of the iOS app, called from `ContentView.swift`. */
@Suppress("unused") // Used in SwiftUI
fun MainViewController(): UIViewController {
    val koin = initKoin {
        modules(module {
            single<ApplicationRepository> { IosApplicationRepository() }
            // Android builds its controller in MainActivity, bound to the activity; iOS needs none.
            single<PermissionsController> { IosPermissionsController() }
        })
    }.koin
    ApplePush.attach(koin.get<AppIntents>())

    return ComposeUIViewController { App() }
}

package es.jvbabi.overmail

import androidx.compose.ui.window.ComposeUIViewController
import es.jvbabi.overmail.data.repository.IosApplicationRepository
import es.jvbabi.overmail.di.initKoin
import es.jvbabi.overmail.domain.repository.ApplicationRepository
import org.koin.dsl.module
import platform.UIKit.UIViewController

/** Entry point of the iOS app, called from `ContentView.swift`. */
@Suppress("unused") // Used in SwiftUI
fun MainViewController(): UIViewController {
    initKoin {
        modules(module {
            single<ApplicationRepository> { IosApplicationRepository() }
        })
    }

    return ComposeUIViewController { App() }
}

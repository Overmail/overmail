package es.jvbabi.overmail

import androidx.compose.ui.window.ComposeUIViewController
import es.jvbabi.overmail.di.initKoin
import platform.UIKit.UIViewController

/** Entry point of the iOS app, called from `ContentView.swift`. */
@Suppress("unused") // Used in SwiftUI
fun MainViewController(): UIViewController {
    initKoin()

    return ComposeUIViewController { App() }
}

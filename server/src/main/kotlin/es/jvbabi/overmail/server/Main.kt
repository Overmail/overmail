package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.http.createHttpServer
import kotlinx.coroutines.runBlocking

fun main() {
    // ImageIO and Batik pull in AWT, and on macOS the first AWT call makes the JVM a GUI app --
    // dock icon and all. Has to happen before any AWT class is touched, so it lives here.
    System.setProperty("java.awt.headless", "true")
    runBlocking {
        createHttpServer().startSuspend(wait = true)
    }
}

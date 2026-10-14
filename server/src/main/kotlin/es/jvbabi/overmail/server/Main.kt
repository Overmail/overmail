package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.ai.chat.FakeAiConfig
import es.jvbabi.overmail.server.http.ServerConfig
import es.jvbabi.overmail.server.http.createHttpServer
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    // ImageIO and Batik pull in AWT, and on macOS the first AWT call makes the JVM a GUI app --
    // dock icon and all. Has to happen before any AWT class is touched, so it lives here.
    System.setProperty("java.awt.headless", "true")
    runBlocking {
        createHttpServer(ServerConfig(fakeAi = FakeAiConfig.fromArguments(args))).startSuspend(wait = true)
    }
}

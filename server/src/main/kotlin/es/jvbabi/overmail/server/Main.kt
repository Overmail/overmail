package es.jvbabi.overmail.server

import es.jvbabi.overmail.server.http.createHttpServer
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        createHttpServer().startSuspend(wait = true)
    }
}

package es.jvbabi.overmail.server.http

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Built in code rather than from an `application.conf`, so the wiring lives next to the rest of
 * the setup and there is no second place that silently configures the server.
 */
fun createHttpServer(config: ServerConfig = ServerConfig()) =
    embeddedServer(Netty, port = config.port, host = config.host) {
        routes()
    }

private fun Application.routes() {
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }
}

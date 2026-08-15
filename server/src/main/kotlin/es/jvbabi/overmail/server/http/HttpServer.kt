package es.jvbabi.overmail.server.http

import es.jvbabi.overmail.server.overmail
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Built in code rather than from an `application.conf`, so the wiring lives next to the rest of
 * the setup and there is no second place that silently configures the server.
 */
fun createHttpServer(config: ServerConfig = ServerConfig()) =
    embeddedServer(Netty, port = config.port, host = config.host) {
        overmail()
    }

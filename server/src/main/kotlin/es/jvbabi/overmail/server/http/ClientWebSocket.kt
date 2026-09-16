package es.jvbabi.overmail.server.http

import io.ktor.server.application.log
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import kotlinx.io.IOException

/**
 * [webSocket] for a browser client, without an error log when that client just went away.
 *
 * A sleeping laptop or a dropped network stops answering pings, and Ktor ends the session by
 * failing `incoming` with `IOException("Ping timeout")` -- which it logs as a handler error.
 * The web app reconnects on its own, so that is debug noise. An IOException on a session that is
 * still open is a real failure and goes through unchanged.
 */
fun Route.clientWebSocket(handler: suspend DefaultWebSocketServerSession.() -> Unit) {
    webSocket {
        try {
            handler()
        } catch (e: IOException) {
            if (!closeReason.isCompleted) throw e
            call.application.log.debug("Websocket ${call.request.uri} closed: ${e.message}")
        }
    }
}

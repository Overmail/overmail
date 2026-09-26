package es.jvbabi.overmail.data.network

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.model.NetworkErrorKind
import es.jvbabi.overmail.domain.model.NetworkException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("ServerSentEvents")

/** How long a stream that broke waits before it is opened again. */
private val RECONNECT_DELAY = 5.seconds

/**
 * Follows the server-sent events at [url] for as long as the caller is collecting, handing the
 * data of each to [onData]. Comments -- the server's keep-alive -- are skipped.
 *
 * A stream that broke or ended is opened again after [reconnectDelay]. One the server refused is
 * not, and this returns: asking again would be refused again. [onData] refuses too, by throwing a
 * [NetworkException] that [isRefusal] says is one; anything else it throws reconnects.
 *
 * @param what what the stream is, for the log.
 */
suspend fun HttpClient.followServerSentEvents(
    url: Url,
    token: String,
    what: String,
    reconnectDelay: Duration = RECONNECT_DELAY,
    onData: suspend (String) -> Unit,
) {
    while (true) {
        val result = safeRequest {
            try {
                sse(request = {
                    url(url)
                    bearerAuth(token)
                }) {
                    incoming.collect { event -> event.data?.let { onData(it) } }
                }
            } catch (e: SSEClientException) {
                // No event stream came back: the server refused before it started, or something
                // in front of it answered instead.
                throw e.response?.toNetworkException() ?: e
            }
        }

        val failure = result.exceptionOrNull()
        if (failure is NetworkException && failure.isRefusal()) {
            logger.e(failure) { "The server refused $what, not asking again" }
            return
        }
        logger.w(failure) { "$what stopped streaming, reconnecting in $reconnectDelay" }
        delay(reconnectDelay)
    }
}

/** An answer from the server that says no, as opposed to one that did not get through. */
fun NetworkException.isRefusal(): Boolean = when (kind) {
    NetworkErrorKind.Unauthorized, NetworkErrorKind.Forbidden, NetworkErrorKind.NotFound -> true
    // A 4xx with the api's error body, or an error the stream itself sent: the request is wrong.
    NetworkErrorKind.Other -> apiErrorCode != null
    NetworkErrorKind.ConnectionError, NetworkErrorKind.ServerError -> false
}

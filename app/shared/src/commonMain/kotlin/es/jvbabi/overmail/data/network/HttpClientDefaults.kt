package es.jvbabi.overmail.data.network

import es.jvbabi.overmail.BuildKonfig
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import co.touchlab.kermit.Logger as KermitLogger

private val clientLogger = KermitLogger.withTag("Ktor Client")

/** The headers [installClientDefaults] keeps out of the log. */
private val sensitiveHeaders = setOf(
    HttpHeaders.Authorization,
    HttpHeaders.Cookie,
    HttpHeaders.SetCookie,
    "Werkbank-Access-Token",
)

/**
 * Retries and logging, the same for every client the app has.
 *
 * Retried is what is worth another try: a request that did not get through, a proxy whose server
 * is restarting, and a 5xx other than 500 from the server itself. A 500 is a bug, and asking
 * again only asks for the same bug -- it is logged instead.
 */
fun HttpClientConfig<*>.installClientDefaults() {
    install(HttpRequestRetry) {
        retryOnException(maxRetries = 2, retryOnTimeout = true)
        exponentialDelay()

        retryIf { request, response ->
            if (!response.isResponseFromBackend()) {
                // Nothing reached the server, so even a POST is safe to send again. A gateway
                // timeout is left out: that request may well have arrived.
                return@retryIf response.status == HttpStatusCode.BadGateway ||
                    response.status == HttpStatusCode.ServiceUnavailable
            }

            if (response.status == HttpStatusCode.InternalServerError) {
                clientLogger.e { "${request.method.value} ${request.url} failed on the server: 500" }
                return@retryIf false
            }
            response.status.value in 500..599
        }
    }

    if (BuildKonfig.LOG_HTTP_REQUESTS) install(Logging) {
        level = LogLevel.HEADERS
        sanitizeHeader { header -> sensitiveHeaders.any { it.equals(header, ignoreCase = true) } }
        logger = object : Logger {
            override fun log(message: String) {
                clientLogger.i { message }
            }
        }
    }
}

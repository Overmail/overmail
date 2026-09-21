package es.jvbabi.overmail.data.network

import io.ktor.client.statement.HttpResponse

/**
 * Set by the server on every response it writes (`http/api/BackendHeaders.kt`), and by nothing
 * that merely stands in front of it. Both values have to match the server's.
 */
const val BACKEND_FAMILY_HEADER = "X-Backend-Family"
const val BACKEND_FAMILY = "Overmail"

/**
 * Whether the Overmail server itself answered, rather than a proxy, a captive portal or
 * werkbank's login page. A status means what the api says it means only when this is true.
 *
 * The header is all there is to go on: the homeserver is whatever host the user signs in to, so
 * unlike an app with one fixed backend there is no host to check against.
 */
fun HttpResponse.isResponseFromBackend(): Boolean =
    headers[BACKEND_FAMILY_HEADER] == BACKEND_FAMILY

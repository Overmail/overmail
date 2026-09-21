package es.jvbabi.overmail.server.http.api

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders

/**
 * Carried by every response this server writes, and by nothing that merely stands in front of it.
 *
 * A status on its own does not say who sent it: a 404 is an unknown code when it comes from here,
 * and a wrong homeserver url when it comes from a proxy, a captive portal or werkbank's login
 * page. The app only takes a status at its word when this header is on it.
 */
const val BACKEND_FAMILY_HEADER = "X-Backend-Family"

/** The value of [BACKEND_FAMILY_HEADER]. The app compares against it, so it does not change. */
const val BACKEND_FAMILY = "Overmail"

/**
 * Installed first, so the header is on everything -- error answers from [installApiErrorHandling]
 * and statuses Ktor produces on its own included.
 */
fun Application.installBackendHeaders() {
    install(DefaultHeaders) {
        header(BACKEND_FAMILY_HEADER, BACKEND_FAMILY)
    }
}

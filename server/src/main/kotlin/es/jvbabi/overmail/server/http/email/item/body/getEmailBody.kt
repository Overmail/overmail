package es.jvbabi.overmail.server.http.email.item.body

import es.jvbabi.overmail.server.http.api.requireOwnedEmailFromUrl
import io.ktor.http.CacheControl
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one mail says: `GET /api/emails/{emailId}/body`.
 *
 * Both parts as they were imported; which of them is rendered is the client's call. A stored mail
 * is never rewritten, so this may sit in a cache for a while.
 */
fun Route.getEmailBody() {
    authenticate {
        get {
            val email = call.requireOwnedEmailFromUrl()

            call.response.cacheControl(
                CacheControl.MaxAge(maxAgeSeconds = 1.hours.inWholeSeconds.toInt())
            )

            call.respond(GetEmailBodyResponse(text = email.textContent, html = email.htmlContent))
        }
    }
}

@Serializable
private data class GetEmailBodyResponse(
    @SerialName("text") val text: String?,
    @SerialName("html") val html: String?,
)

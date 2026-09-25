package es.jvbabi.overmail.server.http.email.item.body

import es.jvbabi.overmail.server.http.api.requireOwnedEmailFromUrl
import io.ktor.http.CacheControl
import io.ktor.openapi.JsonSchema
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
        /**
         * Get the text and html body of a mail.
         *
         * Description: Both parts as they were imported; either can be absent. Cached for an hour, as a stored mail never changes.
         *
         * Tag: Emails
         *
         * Responses:
         *   - 200 [GetEmailBodyResponse] The body
         */
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
    @JsonSchema.Description("The plain text part; null when the mail has none")
    @SerialName("text") val text: String?,
    @JsonSchema.Description("The html part; null when the mail has none")
    @SerialName("html") val html: String?,
)

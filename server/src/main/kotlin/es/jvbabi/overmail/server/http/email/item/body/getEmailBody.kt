package es.jvbabi.overmail.server.http.email.item.body

import es.jvbabi.overmail.server.http.email.getMailFromRequestWithOwnerCheck
import io.ktor.http.CacheControl
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.hours

fun Route.getEmailBody() {
    authenticate {
        get {
            val email = call.getMailFromRequestWithOwnerCheck()

            call.response.cacheControl(CacheControl.MaxAge(
                maxAgeSeconds = 1.hours.inWholeSeconds.toInt()
            ))

            call.respond(
                GetEmailBodyResponse(
                    text = email.textContent,
                    html = email.htmlContent
                )
            )
        }
    }
}

@Serializable
private data class GetEmailBodyResponse(
    @SerialName("text") val text: String?,
    @SerialName("html") val html: String?
)
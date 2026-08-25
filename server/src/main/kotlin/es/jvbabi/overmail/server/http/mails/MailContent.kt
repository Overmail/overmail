package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/mails/{id}/content`. */
fun Route.mailContent() {

    authenticate(SESSION_AUTH) {
        /**
         * The body of one mail, as the text and the HTML part it carried.
         *
         * Its own request rather than a field of the listing: bodies run to tens of thousands of
         * characters each, and a page of them would go over the wire for mails nobody ever opens.
         * A caller lists the headers and asks for the body of the mail it is about to show.
         */
        get("/{id}/content") {
            val email = call.getMailBySlugWithRequiredPrincipalAsOwner()

            call.respond(
                MailContentResponse(
                    id = email.id.toString(),
                    text = email.textContent,
                    html = email.htmlContent,
                )
            )
        }
    }
}

/** The body of one mail, as `GET /api/mails/{id}/content` reports it. */
@Serializable
data class MailContentResponse(
    @SerialName("id") val id: String,
    /** The plain text part, absent when the mail carried none. */
    @SerialName("text") val text: String?,
    /** The HTML part, absent when the mail carried none. */
    @SerialName("html") val html: String?,
)

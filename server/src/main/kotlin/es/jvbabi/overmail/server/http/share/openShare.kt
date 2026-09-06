package es.jvbabi.overmail.server.http.share

import es.jvbabi.overmail.server.data.share.SharePassword
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The same mail with the password typed in: `POST /api/shares/{shareId}/open`.
 *
 * The password comes with the request that reads the mail rather than buying a session first:
 * there is no account here to attach one to, and a page that keeps what the visitor typed for as
 * long as it is open is the whole of what a session would do.
 *
 * A wrong password is a 403 and says only that -- which share the id names, and whether it has a
 * password at all, is what `getShare` already answers. Guessing is slow by construction: every
 * attempt costs a full `SharePassword` derivation. There is no lockout, so a link whose password
 * is a word remains a link whose password is a word.
 */
fun Route.openShare() {
    post {
        val share = call.requireLiveShareFromUrl()
        val request = call.receive<OpenShareRequest>()

        val hash = share.passwordHash
        // Nothing to open: answering "wrong password" for a link that has none would send a
        // reader looking for one, and handing the mail out is what the link says anyway.
        val opened = hash == null || SharePassword.verify(request.password, hash)
        if (!opened) {
            throw ApiException(
                status = HttpStatusCode.Forbidden,
                code = ApiErrorCode.FORBIDDEN,
                message = "That is not the password of this share",
                details = mapOf("resource" to "share"),
            )
        }

        call.respond(call.readSharedEmail(share, unlocked = true))
    }
}

@Serializable
private data class OpenShareRequest(
    @SerialName("password") val password: String,
)

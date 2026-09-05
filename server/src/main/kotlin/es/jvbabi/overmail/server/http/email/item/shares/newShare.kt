package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.database.models.Share
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireOwnedEmailFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hands one mail out to somebody who has no account here: `POST /api/emails/{emailId}/shares`.
 *
 * The answer is the id of the share, which is what the link is built from. A share that has run
 * out is not deleted, so a link that stopped working can be told apart from one that never was.
 */
fun Route.newShare() {
    authenticate {
        post {
            val email = call.requireOwnedEmailFromUrl()
            val request = call.receive<NewShareRequest>()

            val validUntil = Instant.fromEpochSeconds(request.validUntil)
            if (validUntil <= Clock.System.now()) {
                invalidRequest("valid_until", "is in the past", request.validUntil.toString())
            }

            val share = call.database().query {
                Share.new {
                    this.email = email
                    this.shareName = request.shareName
                    this.sharedAt = Clock.System.now()
                    this.validUntil = validUntil
                    this.includeLabels = request.includeLabels
                }
            }

            call.respond(HttpStatusCode.Created, NewShareResponse(share.id.value))
        }
    }
}

@Serializable
private data class NewShareRequest(
    @SerialName("share_name") val shareName: String,
    @SerialName("include_labels") val includeLabels: Boolean,
    /** When the link stops working, as whole seconds since the epoch. */
    @SerialName("valid_until") val validUntil: Long,
)

@Serializable
private data class NewShareResponse(
    @SerialName("share_id") val shareId: Uuid,
)

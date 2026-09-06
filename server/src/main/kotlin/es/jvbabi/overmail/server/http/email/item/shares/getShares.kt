package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Every link that was made for one mail: `GET /api/emails/{emailId}/shares`.
 *
 * The whole list, expired links included -- the share dialog is where they are taken back, and a
 * link that has run out is one the owner may still want to see and delete.
 *
 * Freshest first, so the link somebody just made is the one at the top.
 */
fun Route.getShares() {
    authenticate {
        get {
            val emailId = call.requireOwnedEmailIdFromUrl()

            val shares = call.database().query {
                Shares
                    .selectAll()
                    .where { Shares.email eq emailId }
                    .orderBy(Shares.sharedAt to SortOrder.DESC)
                    .map { row -> row.toSharePayload() }
            }

            call.respond(SharesResponse(shares))
        }
    }
}

@Serializable
private data class SharesResponse(
    @SerialName("shares") val shares: List<SharePayload>,
)

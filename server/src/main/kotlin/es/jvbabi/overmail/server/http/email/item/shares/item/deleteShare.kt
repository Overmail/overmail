package es.jvbabi.overmail.server.http.email.item.shares.item

import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Takes a link back: `DELETE /api/emails/{emailId}/shares/{shareId}`.
 *
 * The row is the link, so this is what makes one stop working for good -- unlike an expiry, which
 * leaves the share standing and tells a visitor that it ran out. Nothing hangs off a share row,
 * and the mail itself is untouched.
 *
 * Scoped to the mail as well as the id, so there is no moment where a share of another mail was
 * matched; a miss on either is the same 404.
 */
fun Route.deleteShare() {
    authenticate {
        delete {
            val emailId = call.requireOwnedEmailIdFromUrl()
            val shareId = shareIdFromPath(call.parameters["shareId"])

            val deleted = call.database().query {
                Shares.deleteWhere { (Shares.id eq shareId) and (Shares.email eq emailId) }
            }
            if (deleted == 0) notFound("share", shareId.toString())

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

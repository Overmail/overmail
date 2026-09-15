package es.jvbabi.overmail.server.http.users.me.views.item

import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere

/**
 * Removes a view: `DELETE /api/users/me/views/{viewId}`.
 *
 * A view is a way of looking at mail and holds none of it, so this takes the row and nothing else
 * -- what was listed under it is where it was before.
 *
 * The neighbours keep their sort keys: they are fractional, and a gap in them is not a gap in the
 * list. Rewriting them would mean touching every other row for a delete.
 *
 * The delete is scoped to the owner as well as the id, so a foreign row is never matched; a miss
 * on either is the same 404, which is also what keeps a delete from confirming that somebody has a
 * view under that id.
 *
 * Announced through [ViewNotifier] like every other change, so the sidebar of every open tab loses
 * the row -- see its note on what the id means for a view that is gone.
 */
fun Route.deleteView() {
    authenticate {
        delete {
            val userId = call.requireAuthenticatedUserId()
            val viewId = viewIdFromPath(call.parameters["viewId"])

            val deleted = call.database().query {
                Views.deleteWhere { (Views.id eq viewId) and (Views.user eq userId) }
            }
            if (deleted == 0) notFound("view", viewId.toString())

            // After the transaction committed: a socket reacting to this re-reads the list, and
            // from inside it would still find the row.
            call.dependency<ViewNotifier>().notifyViewChanged(userId, viewId)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

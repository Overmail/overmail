package es.jvbabi.overmail.server.http.users.me.views.item

import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.models.View
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.database.models.viewSortKeyAfter
import es.jvbabi.overmail.server.database.models.viewSortKeyFirst
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** What fits in `views.name`; a longer one would be cut by the database rather than refused. */
private const val NAME_LIMIT = 255

/**
 * Changes single attributes of a view: `PATCH /api/users/me/views/{viewId}`.
 *
 * A patch, not a put: the screens that write here change one thing at a time -- a rename, a
 * dragged row, a grouping that was switched -- and each of them would otherwise have to send
 * everything else along and could overwrite what another tab wrote in between. What is not in
 * the body stays as it is.
 *
 * Where the view sits is asked for as a neighbour, not as a sort key: `position.after_view_id` is
 * the view it goes behind, null the top of the list, and the key between the two is worked out
 * here. The client therefore never has to generate one, and two clients dragging at once produce
 * two keys rather than the same one.
 *
 * The change is announced through [ViewNotifier], so the sidebar of every open tab follows along
 * -- including the one that asked, which is how it gets its list back in the new order.
 *
 * A view that is not this user's is a 404, not a 403: answering otherwise would confirm the id
 * belongs to somebody.
 */
fun Route.updateView() {
    authenticate {
        patch {
            val userId = call.requireAuthenticatedUserId()
            val viewId = viewIdFromPath(call.parameters["viewId"])
            val request = call.receive<UpdateViewRequest>()

            if (request.name == null && request.view == null && request.position == null) {
                invalidRequest("body", "says nothing to change")
            }

            val name = request.name?.trim()
            if (name != null && name.isEmpty()) invalidRequest("name", "is blank")
            if (name != null && name.length > NAME_LIMIT) {
                invalidRequest("name", "is longer than $NAME_LIMIT characters", name.length.toString())
            }

            val after = request.position?.afterViewId
            if (after == viewId) invalidRequest("after_view_id", "is the view itself", after.toString())

            val updated = call.database().query {
                val owned = Views
                    .select(Views.id)
                    .where { (Views.id eq viewId) and (Views.user eq userId) }
                    .count() > 0L
                if (!owned) notFound("view", viewId.toString())

                // Read and write inside one transaction: a second request moving a view must not
                // land between the keys this one read and the one it writes.
                val sortKey = if (request.position == null) null else {
                    // Without the view being moved: it is leaving its place, and its own key
                    // must not be what the new one is measured against.
                    val others = Views
                        .select(Views.id, Views.sortKey)
                        .where { (Views.user eq userId) and (Views.id neq viewId) }
                        .associate { row -> row[Views.id].value to row[Views.sortKey] }

                    if (after == null) viewSortKeyFirst(others.values.toList())
                    else viewSortKeyAfter(
                        others.values.toList(),
                        others[after] ?: notFound("view", after.toString()),
                    )
                }

                Views.update({ Views.id eq viewId }) { statement ->
                    if (name != null) statement[Views.name] = name
                    if (request.view != null) statement[view] = request.view
                    if (sortKey != null) statement[Views.sortKey] = sortKey
                }

                val row = Views.selectAll().where { Views.id eq viewId }.single()
                UpdatedViewResponse(
                    id = row[Views.id].value,
                    name = row[Views.name],
                    sortKey = row[Views.sortKey],
                    view = row[Views.view],
                )
            }

            // After the transaction committed: a socket reacting to this re-reads the list, and
            // from inside it would read the state from before the write.
            call.dependency<ViewNotifier>().notifyViewChanged(userId, viewId)

            call.respond(HttpStatusCode.OK, updated)
        }
    }
}

@Serializable
private data class UpdateViewRequest(
    /** Trimmed before it is written. Absent leaves the name alone; blank is refused. */
    @SerialName("name") val name: String? = null,
    /** The whole settings object, not a change to it -- there is nothing in it to address. */
    @SerialName("view") val view: ViewSettings? = null,
    /** Absent leaves the view where it is; see [Position] for what null inside it means. */
    @SerialName("position") val position: Position? = null,
) {
    @Serializable
    data class Position(
        /**
         * The view the moved one goes behind, or null for the top of the list.
         *
         * The distinction lives in [UpdateViewRequest.position] being there at all, which is why
         * this is a nested object: `"after_view_id": null` has to be able to mean "to the front"
         * rather than "no move asked for".
         */
        @SerialName("after_view_id") val afterViewId: View.Id? = null,
    )
}

@Serializable
private data class UpdatedViewResponse(
    @SerialName("id") val id: View.Id,
    @SerialName("name") val name: String,
    /** Worked out here for a move, so the caller does not have to guess where the row ended up. */
    @SerialName("sort_key") val sortKey: String,
    @SerialName("view") val view: ViewSettings,
)

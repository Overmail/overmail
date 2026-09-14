package es.jvbabi.overmail.server.http.users.me.views

import es.jvbabi.overmail.server.data.notifier.ViewNotifier
import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.database.models.viewSortKeyAfter
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Adds an empty view: `POST /api/users/me/views/new`.
 *
 * No body. A view is created before it is configured -- the screen wants a row to open and edit,
 * and everything about it is set through later requests -- so there is nothing for a caller to
 * send, and the answer is the row it now has to work with.
 *
 * The name is generated, in the language the user's interface is in, and numbered from the names
 * that exist at this moment rather than from a stored counter. A counter column would drift the
 * first time a view is renamed or deleted, and two tabs creating a view at once would both read
 * the same value out of it anyway. See [nextViewName] for what "empty" and the numbering mean.
 *
 * The new row is announced through [ViewNotifier], so the sidebar of every open tab shows it
 * without the one that asked having to tell the others.
 *
 * The row is placed behind the generated name below it -- "Neue Ansicht 3" lands under "Neue
 * Ansicht 2" wherever the user has dragged that one, and a refilled gap lands back in its gap.
 * Only a name with nothing below it goes to the end of the list.
 */
fun Route.createView() {
    authenticate {
        post {
            val userId = call.requireAuthenticatedUserId()

            val language = pickViewNameLanguage(
                localeCookie = call.request.cookies[LOCALE_COOKIE],
                acceptLanguage = call.request.headers[HttpHeaders.AcceptLanguage],
            )

            // Empty is empty: no grouping at all, nothing filtered out, and the mails in date
            // order, which is what a mailbox looks like before anybody groups it.
            val settings = ViewSettings(
                groupings = listOf(ViewSettings.Grouping.DateSmartGrouping(reversed = false)),
                filter = ViewSettings.Filter.NONE,
                emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
            )

            val created = call.database().query {
                // Read and insert inside one transaction, so a second request creating a view
                // cannot pick the same number, or the same place in the list, between the two
                // statements.
                val existing = Views
                    .select(Views.name, Views.sortKey)
                    .where { Views.user eq userId }
                    .toList()

                val name = nextViewName(language, existing.map { row -> row[Views.name] })

                // The view below the new one, by name. It is a name, not a position, so it holds
                // up after the list has been reordered; when it is absent -- number 1, a renamed
                // predecessor, a filled gap at the top -- the new view goes to the end.
                val predecessor = previousViewName(language, name)
                val after = existing
                    .firstOrNull { row -> row[Views.name] == predecessor }
                    ?.get(Views.sortKey)

                val sortKey = viewSortKeyAfter(existing.map { row -> row[Views.sortKey] }, after)

                val id = Views.insertAndGetId {
                    it[user] = userId
                    it[Views.name] = name
                    it[view] = settings
                    it[Views.sortKey] = sortKey
                }

                CreatedViewResponse(id = id.value, name = name, view = settings, sortKey = sortKey)
            }

            // After the transaction committed: a socket reacting to this re-reads the list, and
            // from inside it would read the state from before the insert.
            call.dependency<ViewNotifier>().notifyViewChanged(userId, created.id)

            call.respond(HttpStatusCode.Created, created)
        }
    }
}

@Serializable
private data class CreatedViewResponse(
    @SerialName("id") val id: Uuid,
    /** Generated, see [nextViewName] -- the caller did not choose it and has to be told. */
    @SerialName("name") val name: String,
    @SerialName("view") val view: ViewSettings,
    /** Where the row goes in the list, see [viewSortKeyAfter] -- generated as well. */
    @SerialName("sort_key") val sortKey: String,
)

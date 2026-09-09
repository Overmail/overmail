package es.jvbabi.overmail.server.http.users.me.views

import es.jvbabi.overmail.server.database.models.ViewSettings
import es.jvbabi.overmail.server.database.models.Views
import es.jvbabi.overmail.server.http.api.database
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
 */
fun Route.createView() {
    authenticate {
        post {
            val userId = call.requireAuthenticatedUserId()

            val language = pickViewNameLanguage(
                localeCookie = call.request.cookies[LOCALE_COOKIE],
                acceptLanguage = call.request.headers[HttpHeaders.AcceptLanguage],
            )

            // Empty is empty: no grouping at all, and the mails in date order, which is what a
            // mailbox looks like before anybody groups it.
            val settings = ViewSettings(
                groupings = listOf(ViewSettings.Grouping.DateSmartGrouping(reversed = false)),
                emailSorting = ViewSettings.EmailSorting.DateSorting(reversed = false),
            )

            val created = call.database().query {
                // Read and insert inside one transaction, so a second request creating a view
                // cannot pick the same number between the two statements.
                val existingNames = Views
                    .select(Views.name)
                    .where { Views.user eq userId }
                    .map { row -> row[Views.name] }

                val name = nextViewName(language, existingNames)

                val id = Views.insertAndGetId {
                    it[user] = userId
                    it[Views.name] = name
                    it[view] = settings
                }

                CreatedViewResponse(id = id.value, name = name, view = settings)
            }

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
)

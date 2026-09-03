package es.jvbabi.overmail.server.http.users.me

import es.jvbabi.overmail.server.auth.user
import io.ktor.http.CacheControl
import io.ktor.server.auth.authenticate
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Private, and short: this is asked on every page load, so it should not go to the server for
 * each navigation -- but a renamed user has to show up again without waiting for a new session.
 */
private val CURRENT_USER_CACHE_DURATION = 5.minutes

/**
 * Who the caller is: `GET /api/users/me`.
 *
 * Costs no query. The session principal is the `users` row that authenticated the request, and
 * these are its own columns, so they are readable after that transaction (see
 * `auth/SessionAuthentication.kt`).
 */
fun Route.getCurrentUser() {
    authenticate {
        get {
            val user = call.user

            call.response.cacheControl(
                CacheControl.MaxAge(
                    maxAgeSeconds = CURRENT_USER_CACHE_DURATION.inWholeSeconds.toInt(),
                    visibility = CacheControl.Visibility.Private,
                )
            )
            call.respond(
                CurrentUserResponse(
                    id = user.id.value,
                    firstname = user.firstname,
                    lastname = user.lastname,
                )
            )
        }
    }
}

@Serializable
private data class CurrentUserResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("firstname") val firstname: String,
    @SerialName("lastname") val lastname: String,
)

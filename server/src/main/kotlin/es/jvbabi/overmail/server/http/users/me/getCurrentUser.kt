package es.jvbabi.overmail.server.http.users.me

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccounts
import io.ktor.http.CacheControl
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
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
 * Their name costs no query -- the session principal is the `users` row that authenticated the
 * request, and these are its own columns (see `auth/SessionAuthentication.kt`). The addresses
 * are one, over the accounts their mail comes in through.
 */
fun Route.getCurrentUser() {
    authenticate {
        get {
            val user = call.user
            val database = application.dependencies.resolve<OvermailDatabase>()

            /**
             * Every address this user is known under: the one their account carries, and the
             * accounts their mail is imported through.
             *
             * An imap login is usually the address, and where it is not -- a bare login name --
             * it is not one and is left out. Lowercase and without duplicates, because this list
             * exists to be compared against the recipients of a mail; the name to show is
             * `email`, which keeps its spelling.
             */
            val addresses = (listOf(user.email) + database.query {
                ImapAccounts
                    .select(ImapAccounts.username)
                    .where { ImapAccounts.user eq user.id }
                    .map { row -> row[ImapAccounts.username] }
            })
                .map { it.trim().lowercase() }
                .filter { it.contains("@") }
                .distinct()

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
                    email = user.email,
                    addresses = addresses,
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
    /** The address of the account, as they wrote it. */
    @SerialName("email") val email: String,
    /**
     * Every address this user receives mail under, lowercase: what a screen compares the
     * recipients of a mail against to find the reader among them.
     */
    @SerialName("addresses") val addresses: List<String>,
)

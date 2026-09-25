package es.jvbabi.overmail.server.http.users.me.sessions.item

import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireOwnedSessionFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

/**
 * Signs a device out: `DELETE /api/users/me/sessions/{sessionId}`.
 *
 * The row stays and is marked revoked, so its token is refused from the next request on -- the
 * JWT itself would otherwise be valid until it runs out. Revoking the current session is allowed
 * and signs this very client out.
 */
fun Route.revokeSession() {
    authenticate {
        /**
         * Sign a session out.
         *
         * Description: Its token is refused from the next request on. Revoking the current session signs the caller out.
         *
         * Tag: Sessions
         *
         * Responses:
         *   - 204 The session is revoked
         */
        delete {
            val session = call.requireOwnedSessionFromUrl()

            call.database().query {
                Sessions.update({ Sessions.id eq session.id }) {
                    it[Sessions.revokedAt] = Clock.System.now()
                }
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

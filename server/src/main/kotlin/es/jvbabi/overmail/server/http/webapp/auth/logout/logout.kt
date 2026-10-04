package es.jvbabi.overmail.server.http.webapp.auth.logout

import es.jvbabi.overmail.server.auth.sessionToken
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.http.api.database
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock

fun Route.logout() {
    /**
     * Sign out and end the session.
     *
     * Description: Revokes the session the request carries, so the token stops working everywhere, and redirects to the start page.
     *
     * Tag: Authentication
     *
     * Responses:
     *   - 302 Redirect to `/`
     */
    get {
        val sessionToken = call.sessionToken()!!

        call.database().query {
            Sessions.update({ Sessions.token eq sessionToken }) {
                it[Sessions.revokedAt] = Clock.System.now()
            }
        }

        call.respondRedirect("/")
    }
}
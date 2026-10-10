package es.jvbabi.overmail.server.http.users.me.sessions.current

import es.jvbabi.overmail.server.auth.sessionToken
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.unauthenticated
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Where a push for this device goes: `PUT /api/users/me/sessions/current/firebase-token`.
 *
 * The token belongs to the session the request came with, not to the user: a push is addressed to
 * one installation, and it has to stop when that session is revoked. Firebase hands out a new one
 * whenever it likes, so the app sends it again each time and the last one wins.
 *
 * Android only for now -- the other clients have nowhere to keep one.
 */
fun Route.setFirebaseToken() {
    authenticate {
        /**
         * Set the Firebase token of the current session.
         *
         * Description: Replaces the one stored before. Only a session of the Android app takes one.
         *
         * Tag: Sessions
         *
         * Body: [FirebaseTokenRequest] The token
         *
         * Responses:
         *   - 204 The token is saved
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A blank `token`
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The session is not one of the Android app
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val sessionToken = call.sessionToken() ?: unauthenticated()
            val firebaseToken = call.receive<FirebaseTokenRequest>().token.trim()
            if (firebaseToken.isEmpty()) invalidRequest("token", "must not be blank")

            val saved = call.database().query {
                val isCurrentSession = (Sessions.token eq sessionToken) and (Sessions.user eq userId)
                val client = Sessions.select(Sessions.client).where { isCurrentSession }.single()[Sessions.client]
                if (client !is Session.Client.Android) return@query false

                Sessions.update({ isCurrentSession }) { it[Sessions.client] = client.copy(firebaseToken = firebaseToken) }
                true
            }
            if (!saved) conflict("Only a session of the Android app takes a Firebase token")

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class FirebaseTokenRequest(
    @JsonSchema.Description("The registration token Firebase Cloud Messaging gave this installation")
    @SerialName("token") val token: String,
)

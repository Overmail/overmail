package es.jvbabi.overmail.server.http.users.me.password

import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/** Whether the caller signs in with a password: `GET /api/users/me/password`. The hash never leaves. */
fun Route.getPassword() {
    authenticate {
        /**
         * Get whether a password is set.
         *
         * Description: Without one, the account signs in with a code mailed to its address.
         *
         * Tag: Account
         *
         * Responses:
         *   - 200 [PasswordStatusResponse] Whether one is set
         */
        get {
            val userId = call.requireAuthenticatedUserId()
            val isSet = call.database().query {
                Users.select(Users.password).where { Users.id eq userId }.single()[Users.password] != null
            }
            call.respond(PasswordStatusResponse(isSet))
        }
    }
}

@Serializable
data class PasswordStatusResponse(
    @SerialName("is_set") val isSet: Boolean,
)

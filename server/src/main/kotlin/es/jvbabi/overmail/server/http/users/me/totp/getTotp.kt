package es.jvbabi.overmail.server.http.users.me.totp

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

/** Whether the sign-in asks for an authenticator code: `GET /api/users/me/totp`. The secret stays. */
fun Route.getTotp() {
    authenticate {
        /**
         * Get whether an authenticator app is set up.
         *
         * Description: With one, the sign-in asks for its code after the password.
         *
         * Tag: Account
         *
         * Responses:
         *   - 200 [TotpStatusResponse] Whether one is set up
         */
        get {
            val userId = call.requireAuthenticatedUserId()
            val isEnabled = call.database().query {
                Users.select(Users.totpSecret).where { Users.id eq userId }.single()[Users.totpSecret] != null
            }
            call.respond(TotpStatusResponse(isEnabled))
        }
    }
}

@Serializable
data class TotpStatusResponse(
    @SerialName("is_enabled") val isEnabled: Boolean,
)

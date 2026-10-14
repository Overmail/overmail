package es.jvbabi.overmail.server.http.users.me.sessions.current

import es.jvbabi.overmail.server.auth.sessionToken
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.unauthenticated
import es.jvbabi.overmail.server.jobs.push.ApplePushSealing
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
import java.util.Base64

/** An APNs device token: 32 bytes as hex, longer ones are allowed for. */
private val DEVICE_TOKEN = Regex("[0-9a-f]{64,200}")

/**
 * Where a push for this iOS device goes and what it is sealed for:
 * `PUT /api/users/me/sessions/current/apple-push`.
 *
 * The counterpart of [setFirebaseInstallationId]. It belongs to the session the request came with,
 * so a push stops when that session is revoked, and the last one sent wins. The public key is the
 * device's: what a notification shows is encrypted for it, see `ApplePushSealing`.
 */
fun Route.setApplePush() {
    authenticate {
        /**
         * Set where a push for the current iOS session goes.
         *
         * Description: Replaces what was stored before. Only a session of the iOS app takes one.
         *
         * Tag: Sessions
         *
         * Body: [ApplePushRequest] The device token, its environment and the public key
         *
         * Responses:
         *   - 204 The registration is saved
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A token that is not hex, or a key that is not a P-256 point
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The session is not one of the iOS app
         */
        put {
            val userId = call.requireAuthenticatedUserId()
            val sessionToken = call.sessionToken() ?: unauthenticated()
            val request = call.receive<ApplePushRequest>()

            val deviceToken = request.token.trim().lowercase()
            if (!DEVICE_TOKEN.matches(deviceToken)) invalidRequest("token", "must be the device token in hex")
            val publicKey = request.publicKey.trim()
            val isPublicKey = runCatching { ApplePushSealing.publicKeyOf(Base64.getDecoder().decode(publicKey)) }.isSuccess
            if (!isPublicKey) invalidRequest("public_key", "must be an uncompressed P-256 point in base64")

            val registration = Session.Client.ApplePushRegistration(
                token = deviceToken,
                environment = request.environment,
                publicKey = publicKey,
            )
            val saved = call.database().query {
                val isCurrentSession = (Sessions.token eq sessionToken) and (Sessions.user eq userId)
                val client = Sessions.select(Sessions.client).where { isCurrentSession }.single()[Sessions.client]
                if (client !is Session.Client.Ios) return@query false

                Sessions.update({ isCurrentSession }) { it[Sessions.client] = client.copy(applePush = registration) }
                true
            }
            if (!saved) conflict("Only a session of the iOS app takes an APNs registration")

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Serializable
data class ApplePushRequest(
    @JsonSchema.Description("The APNs device token of the app on this device, in hex")
    @SerialName("token") val token: String,
    @JsonSchema.Description("Which APNs issued the token: `sandbox` for a debug build, `production` otherwise")
    @SerialName("environment") val environment: Session.Client.ApnsEnvironment,
    @JsonSchema.Description("The device's P-256 public key, uncompressed X9.63 in base64; what a notification shows is encrypted for it")
    @SerialName("public_key") val publicKey: String,
)

package es.jvbabi.overmail.server.http.users.me.sessions

import es.jvbabi.overmail.server.auth.sessionToken
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where this user is signed in: `GET /api/users/me/sessions`.
 *
 * Only sessions that still work -- a revoked one signs nobody in and has nothing left to do in
 * this list. Newest first. Neither the token itself nor a Firebase installation id leaves the server;
 * `is_current_session` is how a client finds its own entry.
 */
fun Route.getSessions() {
    authenticate {
        /**
         * List the sessions of the current user.
         *
         * Description: Every session that was not revoked, newest first. `is_current_session` marks the caller's own.
         *
         * Tag: Sessions
         *
         * Responses:
         *   - 200 [SessionsResponse] The sessions
         */
        get {
            val userId = call.requireAuthenticatedUserId()
            val currentToken = call.sessionToken()

            val sessions = call.database().query {
                Sessions
                    .select(Sessions.id, Sessions.client, Sessions.issuedAt, Sessions.token)
                    .where { (Sessions.user eq userId) and Sessions.revokedAt.isNull() }
                    .orderBy(Sessions.issuedAt to SortOrder.DESC)
                    .map { row ->
                        SessionPayload(
                            id = row[Sessions.id].value,
                            client = row[Sessions.client].withoutSecrets(),
                            issuedAt = row[Sessions.issuedAt],
                            isCurrentSession = row[Sessions.token] == currentToken,
                            pushNotificationState = row[Sessions.client].pushNotificationState(),
                        )
                    }
            }

            call.respond(SessionsResponse(sessions))
        }
    }
}

/** The client as a listing may show it: a push token addresses a device and stays on the server. */
private fun Session.Client.withoutSecrets(): Session.Client = when (this) {
    is Session.Client.Android -> copy(firebaseInstallationId = null)
    is Session.Client.Ios -> copy(applePush = null)
    is Session.Client.Web -> this
}

/** Whether a push reaches this client: only the Android app takes one, once it registered with Firebase. */
private fun Session.Client.pushNotificationState(): PushNotificationState = when (this) {
    is Session.Client.Android ->
        if (firebaseInstallationId == null) PushNotificationState.Available else PushNotificationState.Setup
    is Session.Client.Web, is Session.Client.Ios -> PushNotificationState.Unavailable
}

@Serializable
private enum class PushNotificationState {
    @SerialName("unavailable") Unavailable,
    @SerialName("available") Available,
    @SerialName("setup") Setup,
}

@Serializable
private data class SessionsResponse(
    @SerialName("sessions") val sessions: List<SessionPayload>,
)

@Serializable
private data class SessionPayload(
    @SerialName("id") val id: Uuid,
    @SerialName("client") val client: Session.Client,
    @JsonSchema.Description("When the session was issued, as ISO-8601")
    @SerialName("issued_at") val issuedAt: Instant,
    @JsonSchema.Description("Whether it is the session this request came with")
    @SerialName("is_current_session") val isCurrentSession: Boolean,
    @JsonSchema.Description("`unavailable` when the client cannot take a push at all, `available` when it could but has not registered for one, `setup` when a push reaches it")
    @SerialName("push_notification_state") val pushNotificationState: PushNotificationState,
)

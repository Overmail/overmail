package es.jvbabi.overmail.server.http.users.me.sessions.item

import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.requireOwnedSessionFromUrl
import es.jvbabi.overmail.server.http.api.unavailable
import es.jvbabi.overmail.server.jobs.push.PushNotifications
import es.jvbabi.overmail.server.jobs.push.PushTarget
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Has one device show a test notification: `POST /api/users/me/sessions/{sessionId}/test-push`.
 *
 * Proves the whole way -- the server's credentials, Firebase or APNs, the device's token and the
 * app -- from the web app. The push is queued, so the answer says nothing about whether it
 * arrived; the notification on the device is what does.
 */
fun Route.sendTestPush() {
    authenticate {
        /**
         * Send a test push to a session.
         *
         * Description: Queued, not delivered: the device shows a test notification when it arrives.
         *
         * Tag: Sessions
         *
         * Responses:
         *   - 202 The push is queued
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The session has not set up push
         *   - 503 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The server has no credentials for the device's platform, so no push goes out
         */
        post {
            val session = call.requireOwnedSessionFromUrl()
            val client = session.client
            if (!client.hasPushToken) conflict("The session has not set up push notifications")

            val pushNotifications = call.dependency<PushNotifications>()
            // Without credentials the push would be dropped in silence, which looks like a broken device.
            if (!pushNotifications.reaches(client)) {
                unavailable("Push notifications to this device are not configured on this server")
            }

            pushNotifications.ping(PushTarget.Session(session.id.value))

            call.respond(HttpStatusCode.Accepted)
        }
    }
}

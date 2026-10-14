package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.models.Session

/**
 * Hands one push to APNs, the counterpart of [PushSender] for iOS. Separate because what it sends
 * is not a map of data but the notification itself, see [ApplePushPayloads].
 */
fun interface ApplePushSender {
    /** Sends [notification] to [device]. Does not throw -- the result says what became of it. */
    suspend fun send(device: Session.Client.ApplePushRegistration, notification: ApplePushNotification): PushSender.Result

    /** What stands in while the server has no APNs key: nothing goes to iOS. */
    data object Disabled : ApplePushSender {
        override suspend fun send(device: Session.Client.ApplePushRegistration, notification: ApplePushNotification) =
            PushSender.Result.Failed("Push to iOS is not configured", isRetryable = false)
    }
}

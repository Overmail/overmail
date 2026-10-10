package es.jvbabi.overmail.data.push

import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private val logger = Logger.withTag("PushMessagingService")

/**
 * What Firebase calls into: a push that arrived, and a registration token that was issued.
 *
 * Declared in the manifest of `:app:android`, and started by the system on its own -- also while
 * the app is not running, so nothing here may count on an activity.
 */
class PushMessagingService : FirebaseMessagingService() {

    /** Firebase replaces the token whenever it likes: on first start, a restore, cleared app data. */
    override fun onNewToken(token: String) {
        // Never the token itself, it addresses this device.
        logger.i { "Firebase issued a new registration token" }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        logger.i { "Push received: ${message.data.keys}" }
    }
}

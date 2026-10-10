package es.jvbabi.overmail.data.push

import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import es.jvbabi.overmail.domain.model.ReceivedPush
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val logger = Logger.withTag("PushMessagingService")

/**
 * What Firebase calls into: a push that arrived, and a registration token that was issued.
 *
 * Declared in the manifest of `:app:android`, and started by the system on its own -- also while
 * the app is not running, so nothing here may count on an activity.
 */
class PushMessagingService : FirebaseMessagingService(), KoinComponent {

    private val pushTokens: FirebasePushTokenRepository by inject()
    private val pushNotifier: PushNotifier by inject()

    /** Firebase replaces the token whenever it likes: on first start, a restore, cleared app data. */
    override fun onNewToken(token: String) {
        // Never the token itself, it addresses this device.
        logger.i { "Firebase issued a new registration token" }
        pushTokens.onNewToken(token)
    }

    /**
     * Called on a thread of Firebase's own, which may be kept for about ten seconds -- so the
     * work is done right here rather than handed to a scope the process could die under.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val push = ReceivedPush.fromData(message.data)
        if (push == null) {
            logger.w { "Ignoring a push this version cannot read" }
            return
        }

        logger.i { "Push received: ${push.message}" }
        runBlocking { pushNotifier.handle(push) }
    }
}

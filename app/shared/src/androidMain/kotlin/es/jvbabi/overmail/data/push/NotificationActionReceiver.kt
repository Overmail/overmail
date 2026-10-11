package es.jvbabi.overmail.data.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private val logger = Logger.withTag("NotificationActionReceiver")

private const val ACTION_ARCHIVE = "es.jvbabi.overmail.action.ARCHIVE_EMAIL"
private const val EXTRA_ACCOUNT_ID = "account_id"
private const val EXTRA_EMAIL_ID = "email_id"

/**
 * What a button on a notification does without opening the app: a broadcast to here, where a tap
 * on the notification itself is an `AppIntent` to the activity. Declared in the manifest of
 * `:app:android`, not exported -- only the app's own notifications reach it.
 */
class NotificationActionReceiver : BroadcastReceiver(), KoinComponent {

    private val pushNotifier: PushNotifier by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ARCHIVE) return
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let(Uuid::parseOrNull) ?: return
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID)?.let(Uuid::parseOrNull) ?: return

        // The request to the server outlives onReceive; the system keeps the process for it until
        // finish(), for about ten seconds.
        val pending = goAsync()
        scope.launch {
            try {
                pushNotifier.archive(accountId, emailId)
            } catch (cause: Exception) {
                logger.w(cause) { "Could not archive mail $emailId from its notification" }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The archive button of the notification of the mail [emailId]. */
        fun archive(context: Context, accountId: Uuid, emailId: Uuid): PendingIntent = PendingIntent.getBroadcast(
            context,
            // Extras do not tell two pending intents apart, so every mail needs a request code of its own.
            emailId.hashCode(),
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_ARCHIVE)
                .putExtra(EXTRA_ACCOUNT_ID, accountId.toString())
                .putExtra(EXTRA_EMAIL_ID, emailId.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

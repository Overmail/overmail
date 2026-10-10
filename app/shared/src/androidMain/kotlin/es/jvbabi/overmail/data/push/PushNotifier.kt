package es.jvbabi.overmail.data.push

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.data.repository.NotificationChannelRepositoryImpl
import es.jvbabi.overmail.domain.model.PushMessage
import es.jvbabi.overmail.domain.model.ReceivedPush
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import es.jvbabi.overmail.shared.compose.R
import org.jetbrains.compose.resources.getString
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.notifications_ping_text
import overmail.app.shared.generated.resources.notifications_ping_title

/** One id per kind of notification that replaces itself; a second ping is not a second notification. */
private const val PING_NOTIFICATION_ID = 1

/**
 * Turns a push into what the user sees. A push only says what happened, so everything shown
 * comes from here or is fetched from the account's server -- never out of the push itself.
 */
class PushNotifier(
    private val context: Context,
    private val notificationChannelRepository: NotificationChannelRepository,
) {
    private val logger = Logger.withTag("PushNotifier")

    private val notificationManager = NotificationManagerCompat.from(context)

    suspend fun handle(push: ReceivedPush) {
        when (push.message) {
            PushMessage.Ping -> showPing()
        }
    }

    private suspend fun showPing() {
        // A push can start the app's process on its own, before the setup created any channel.
        notificationChannelRepository.createSystemChannel()

        post(
            PING_NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannelRepositoryImpl.SYSTEM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(Res.string.notifications_ping_title))
                .setContentText(getString(Res.string.notifications_ping_text))
                .setAutoCancel(true),
        )
    }

    // Checked by hand: lint only recognises a permission check, and this covers a switched-off
    // channel-less app as well.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: NotificationCompat.Builder) {
        if (!notificationManager.areNotificationsEnabled()) {
            logger.i { "Notifications are switched off for the app, not showing one" }
            return
        }
        notificationManager.notify(id, notification.build())
    }
}

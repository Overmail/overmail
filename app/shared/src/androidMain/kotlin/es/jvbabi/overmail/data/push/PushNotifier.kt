package es.jvbabi.overmail.data.push

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import co.touchlab.kermit.Logger
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import es.jvbabi.overmail.data.network.appImageLoader
import es.jvbabi.overmail.data.network.avatarRequest
import es.jvbabi.overmail.data.repository.NotificationChannelRepositoryImpl
import es.jvbabi.overmail.domain.intent.AppIntent
import es.jvbabi.overmail.domain.model.Email
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.model.PushMessage
import es.jvbabi.overmail.domain.model.ReceivedPush
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.EmailsRepository
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import es.jvbabi.overmail.shared.compose.R
import es.jvbabi.overmail.ui.components.initials
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.email_no_subject
import overmail.app.shared.generated.resources.notifications_new_email_fallback_title
import overmail.app.shared.generated.resources.notifications_ping_text
import overmail.app.shared.generated.resources.notifications_ping_title
import kotlin.time.Duration.Companion.seconds

/** One id per kind of notification that replaces itself; a second ping is not a second notification. */
private const val PING_NOTIFICATION_ID = 1

/** A mail's notification is told apart by its tag, the mail's id, so they all share this one. */
private const val EMAIL_NOTIFICATION_ID = 2

/**
 * How long a mail may take to load before its notification goes out without it. Firebase gives a
 * push about ten seconds in all, and the picture and the text still come after this.
 */
private val EMAIL_TIMEOUT = 6.seconds
private val BODY_TIMEOUT = 2.seconds
private val AVATAR_TIMEOUT = 2.seconds

/** How much of a mail's text a notification carries; more than it shows expanded is wasted. */
private const val MAX_TEXT_LENGTH = 500

/**
 * Turns a push into what the user sees. A push only says what happened, so everything shown
 * comes from here or is fetched from the account's server -- never out of the push itself.
 */
class PushNotifier(
    private val context: Context,
    private val notificationChannelRepository: NotificationChannelRepository,
    private val accountRepository: AccountRepository,
    private val emailsRepository: EmailsRepository,
    private val httpClient: HttpClient,
) {
    private val logger = Logger.withTag("PushNotifier")

    private val notificationManager = NotificationManagerCompat.from(context)

    suspend fun handle(push: ReceivedPush) {
        when (val message = push.message) {
            is PushMessage.NewEmail -> showNewEmail(push.userId, message)
            PushMessage.Ping -> showPing()
        }
    }

    private suspend fun showPing() {
        // A push can start the app's process on its own, before the setup created any channel.
        notificationChannelRepository.createSystemChannel()

        post(
            tag = null,
            id = PING_NOTIFICATION_ID,
            NotificationCompat.Builder(context, NotificationChannelRepositoryImpl.SYSTEM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(Res.string.notifications_ping_title))
                .setContentText(getString(Res.string.notifications_ping_text))
                .setAutoCancel(true),
        )
    }

    /**
     * The notification of a mail: who it is from with their picture, its subject, and how it
     * begins. Loading it for that is also what puts the mail and its body into the cache, so the
     * page a tap opens has them already.
     */
    private suspend fun showNewEmail(userId: kotlin.uuid.Uuid, message: PushMessage.NewEmail) {
        // The account's id is the user's on its homeserver. Signed out since: not ours to show.
        val account = accountRepository.getById(userId).first() ?: run {
            logger.i { "A push for an account that is not signed in here, ignoring it" }
            return
        }

        val email = withTimeoutOrNull(EMAIL_TIMEOUT) {
            emailsRepository.getEmail(message.emailId, account).filterNotNull().first()
        }

        val notification = if (email != null) emailNotification(email, account) else fallbackNotification(message, account)
        post(
            tag = message.emailId.toString(),
            id = EMAIL_NOTIFICATION_ID,
            notification
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setContentIntent(open(AppIntent.OpenEmail(accountId = account.id, emailId = message.emailId)))
                .setAutoCancel(true),
        )
    }

    private suspend fun emailNotification(email: Email, account: OvermailAccount): NotificationCompat.Builder {
        // Before the notification, and with the mailbox the mail names: the push may be the first
        // the app hears of a mailbox that was connected elsewhere.
        notificationChannelRepository.createAccountChannels(account, listOf(email.imapAccount), removeOthers = false)

        val subject = email.subject?.takeIf { it.isNotBlank() } ?: getString(Res.string.email_no_subject)
        val text = textOf(email, account)

        return NotificationCompat.Builder(context, NotificationChannelRepositoryImpl.imapAccountChannelId(email.imapAccount.id))
            .setContentTitle(email.sentBy.displayName)
            .setContentText(subject)
            // Which mailbox, for whoever has several.
            .setSubText(email.imapAccount.username)
            .setLargeIcon(avatarOf(email.sentBy))
            .setWhen(email.sentAt.toEpochMilliseconds())
            .setShowWhen(true)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(if (text.isNullOrEmpty()) subject else "$subject\n$text")
            )
    }

    /** What is shown for a mail that could not be loaded in time: that there is one, and the way to it. */
    private suspend fun fallbackNotification(message: PushMessage.NewEmail, account: OvermailAccount): NotificationCompat.Builder {
        logger.w { "Could not load mail ${message.emailId} in time, showing its notification without it" }
        notificationChannelRepository.createSystemChannel()

        return NotificationCompat.Builder(context, NotificationChannelRepositoryImpl.SYSTEM_CHANNEL_ID)
            .setContentTitle(getString(Res.string.notifications_new_email_fallback_title))
            .setContentText(account.email)
    }

    /**
     * How the mail begins, as running text. Its text part, which is fetched into the cache here;
     * the server's one-line preview for a mail that is html alone or whose body did not come.
     */
    private suspend fun textOf(email: Email, account: OvermailAccount): String? {
        val body = withTimeoutOrNull(BODY_TIMEOUT) { emailsRepository.getBody(email.id, account).getOrNull() }
        val text = body?.text?.takeIf { it.isNotBlank() } ?: email.preview
        return text?.replace(WHITESPACE, " ")?.trim()?.take(MAX_TEXT_LENGTH)
    }

    /** The sender's picture as it is, a logo keeps its shape; their initials while there is none. */
    private suspend fun avatarOf(participant: Participant): Bitmap {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)

        val request = participant.avatarRequest(context)
            ?.size(size)
            // A notification is drawn by another process, which cannot read a hardware bitmap.
            ?.allowHardware(false)
            ?.build()
            ?: return initialsOf(participant, size)

        // The loader of the whole app, so a picture a screen loaded is here already and the other
        // way around. `App` sets the same one up; whoever comes first does.
        SingletonImageLoader.setSafe { appImageLoader(it, httpClient) }
        val result = withTimeoutOrNull(AVATAR_TIMEOUT) { SingletonImageLoader.get(context).execute(request) }
        return (result as? SuccessResult)?.image?.toBitmap(size, size) ?: initialsOf(participant, size)
    }

    /** The round initials the app shows for a correspondent without a picture, see `ParticipantAvatar`. */
    private fun initialsOf(participant: Participant, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INITIALS_BACKGROUND }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle)

        val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INITIALS_FOREGROUND
            textSize = size * 0.4f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // Centred on the letters, not on the baseline.
        val baseline = size / 2f - (letters.descent() + letters.ascent()) / 2f
        canvas.drawText(initials(participant.displayName), size / 2f, baseline, letters)

        return bitmap
    }

    /**
     * What a tap on a notification does: the app is opened with [intent] as a link, and the
     * shared code acts on it, see [AppIntent]. Resolved through the manifest's filter for the
     * app's scheme rather than by naming the activity, which lives in `:app:android`.
     */
    private fun open(intent: AppIntent): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(Intent.ACTION_VIEW, intent.toUri().toUri()).setPackage(context.packageName),
        // The uri tells two of them apart, so each notification keeps its own.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // Checked by hand: lint only recognises a permission check, and this covers an app whose
    // notifications were switched off on an older Android as well.
    @SuppressLint("MissingPermission")
    private fun post(tag: String?, id: Int, notification: NotificationCompat.Builder) {
        if (!notificationManager.areNotificationsEnabled()) {
            logger.i { "Notifications are switched off for the app, not showing one" }
            return
        }
        notificationManager.notify(tag, id, notification.build())
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        // The launcher icon's paper and ink: a notification has no theme to take colours from.
        const val INITIALS_BACKGROUND = 0xFFE6E3DA.toInt()
        const val INITIALS_FOREGROUND = 0xFF1C1B1A.toInt()
    }
}

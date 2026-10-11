package es.jvbabi.overmail.data.repository

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import es.jvbabi.overmail.shared.compose.R
import org.jetbrains.compose.resources.getString
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.notifications_channel_mailbox_description
import overmail.app.shared.generated.resources.notifications_channel_mailbox_name
import overmail.app.shared.generated.resources.notifications_channel_system_description
import overmail.app.shared.generated.resources.notifications_channel_system_name
import overmail.app.shared.generated.resources.notifications_group_account_description
import kotlin.uuid.Uuid

/**
 * Notification channels, with an account as a channel group and each of its mailboxes as a channel
 * in it.
 *
 * Creating one that exists changes its name and description and nothing else, so everything here can be repeated:
 * importance, sound and the rest belong to the user from the first time on. Before Android 8 there
 * are no channels, and the compat classes make all of this a no-op.
 */
class NotificationChannelRepositoryImpl(private val context: Context) : NotificationChannelRepository {

    private val notificationManager = NotificationManagerCompat.from(context)

    override suspend fun createSystemChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(SYSTEM_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(Res.string.notifications_channel_system_name))
                .setDescription(getString(Res.string.notifications_channel_system_description))
                .build()
        )
    }

    override suspend fun createAccountChannels(account: OvermailAccount, imapAccounts: List<ImapAccount>, removeOthers: Boolean) {
        val groupId = accountGroupId(account.id)
        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroupCompat.Builder(groupId)
                .setName(account.email)
                .setDescription(getString(Res.string.notifications_group_account_description, account.email, account.homeserver))
                .build()
        )

        notificationManager.createNotificationChannelsCompat(imapAccounts.map { imapAccount ->
            NotificationChannelCompat.Builder(imapAccountChannelId(imapAccount.id), NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(Res.string.notifications_channel_mailbox_name, imapAccount.username))
                .setDescription(getString(Res.string.notifications_channel_mailbox_description, imapAccount.username, imapAccount.host))
                .setGroup(groupId)
                .setSound(newEmailSound(context), NEW_EMAIL_SOUND_ATTRIBUTES)
                .build()
        })

        if (!removeOthers) return
        val keep = imapAccounts.map { imapAccountChannelId(it.id) }.toSet()
        notificationManager.notificationChannelsCompat
            .filter { it.group == groupId && it.id !in keep }
            .forEach { notificationManager.deleteNotificationChannel(it.id) }
    }

    override suspend fun removeOtherAccounts(accounts: List<OvermailAccount>) {
        val keep = accounts.map { accountGroupId(it.id) }.toSet()
        notificationManager.notificationChannelGroupsCompat
            .filter { it.id.startsWith(ACCOUNT_GROUP_PREFIX) && it.id !in keep }
            // Takes the channels in it along.
            .forEach { notificationManager.deleteNotificationChannelGroup(it.id) }
    }

    companion object {
        /** What the app itself has to say, rather than a mail. */
        const val SYSTEM_CHANNEL_ID = "system"

        private const val ACCOUNT_GROUP_PREFIX = "account:"

        /** The group of an Overmail account. */
        fun accountGroupId(overmailAccountId: Uuid) = "$ACCOUNT_GROUP_PREFIX$overmailAccountId"

        /**
         * The channel new mail of a mailbox is posted to.
         *
         * A channel keeps the sound it was created with, and one that is deleted comes back with
         * its old settings under the same id. So a new default for the sound is a new prefix here;
         * the channels under the old one go the next time the server confirms the mailboxes.
         */
        fun imapAccountChannelId(imapAccountId: Uuid) = "mailbox:$imapAccountId"

        /** The app's own sound for a new mail, `res/raw/notification_new_email`. */
        fun newEmailSound(context: Context): Uri =
            "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.notification_new_email}".toUri()

        private val NEW_EMAIL_SOUND_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

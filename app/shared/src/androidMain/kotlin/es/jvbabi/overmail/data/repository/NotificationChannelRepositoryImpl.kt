package es.jvbabi.overmail.data.repository

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import org.jetbrains.compose.resources.getString
import overmail.app.shared.generated.resources.Res
import overmail.app.shared.generated.resources.notifications_channel_system_description
import overmail.app.shared.generated.resources.notifications_channel_system_name
import kotlin.uuid.Uuid

/**
 * Notification channels, with an account as a channel group and each of its mailboxes as a channel
 * in it.
 *
 * Creating one that exists changes its name and nothing else, so everything here can be repeated:
 * importance, sound and the rest belong to the user from the first time on. Before Android 8 there
 * are no channels, and the compat classes make all of this a no-op.
 */
class NotificationChannelRepositoryImpl(context: Context) : NotificationChannelRepository {

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
            NotificationChannelGroupCompat.Builder(groupId).setName(account.email).build()
        )

        notificationManager.createNotificationChannelsCompat(imapAccounts.map { imapAccount ->
            NotificationChannelCompat.Builder(imapAccountChannelId(imapAccount.id), NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(imapAccount.username)
                .setGroup(groupId)
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

        /** The channel new mail of a mailbox is posted to. */
        fun imapAccountChannelId(imapAccountId: Uuid) = "inbox:$imapAccountId"
    }
}

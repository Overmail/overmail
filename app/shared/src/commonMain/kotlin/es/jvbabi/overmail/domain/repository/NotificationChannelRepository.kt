package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount

/**
 * What the system lets a user tune notifications by. Platform-specific all the way down: on
 * Android these are notification channels, iOS has nothing of the kind.
 *
 * An account is a category and each of its mailboxes a channel in it, so mail to one address can
 * be silenced without the others. Everything that is not a mail goes to one channel of its own.
 */
interface NotificationChannelRepository {
    /** The channel for what the app itself has to say. Safe to repeat. */
    suspend fun createSystemChannel()

    /**
     * Creates the category of [account] and a channel for each of [imapAccounts]. Safe to repeat:
     * what is there already keeps the settings the user gave it.
     *
     * @param removeOthers also remove the channels of mailboxes that are not in [imapAccounts].
     *   Only for a list the server confirmed -- a cache that is not filled yet is empty too.
     */
    suspend fun createAccountChannels(account: OvermailAccount, imapAccounts: List<ImapAccount>, removeOthers: Boolean)

    /** Removes the categories, and with them the channels, of every account not in [accounts]. */
    suspend fun removeOtherAccounts(accounts: List<OvermailAccount>)
}

package es.jvbabi.overmail.domain.usecase.housekeeping

import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import es.jvbabi.overmail.domain.repository.NotificationChannelRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SyncNotificationChannelsUseCase(
    private val accountRepository: AccountRepository,
    private val imapAccountsRepository: ImapAccountsRepository,
    private val notificationChannelRepository: NotificationChannelRepository,
) {
    /**
     * Keeps the notification channels in step with who is signed in and which mailboxes they
     * have: a category per account, a channel per mailbox, and the one for the app itself.
     * Suspends for as long as it runs.
     */
    suspend operator fun invoke() {
        notificationChannelRepository.createSystemChannel()

        accountRepository.getAccounts()
            .distinctUntilChanged()
            .collectLatest { accounts ->
                notificationChannelRepository.removeOtherAccounts(accounts)
                coroutineScope {
                    accounts.forEach { account -> launch { syncAccount(account) } }
                }
            }
    }

    private suspend fun syncAccount(account: OvermailAccount) {
        // The cache first, so the channels are there without a connection as well.
        imapAccountsRepository.getAll(instantLocalEmission = true, overmailAccount = account).collect { imapAccounts ->
            notificationChannelRepository.createAccountChannels(
                account = account,
                imapAccounts = imapAccounts.data,
                removeOthers = imapAccounts.source == CacheableResource.Source.Network,
            )
        }
    }
}

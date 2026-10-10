package es.jvbabi.overmail.domain.usecase.housekeeping

import co.touchlab.kermit.Logger
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy

class RegisterPushTokenUseCase(
    private val accountRepository: AccountRepository,
    private val pushTokenRepository: PushTokenRepository,
) {
    private val logger = Logger.withTag("RegisterPushToken")

    /**
     * Tells the server of every account where a push for this device goes, and again whenever an
     * account is added or the platform replaces the token. Suspends for as long as it runs.
     *
     * The server keeps the token on the session, so every account gets it, not only the current
     * one. Sending it again is harmless: the last one wins.
     */
    suspend operator fun invoke() {
        val accounts = accountRepository.getAccounts()
            // Only who is signed in matters, not what else changed about them. The session token
            // is part of it: signing in again is a new session that has no push token yet.
            .distinctUntilChangedBy { accounts -> accounts.map { it.id to it.token } }

        combine(accounts, pushTokenRepository.getToken()) { accounts, pushToken -> accounts to pushToken }
            .collectLatest { (accounts, pushToken) ->
                accounts.forEach { account ->
                    accountRepository.setPushToken(account, pushToken).onFailure { error ->
                        logger.w(error) { "Could not register the push token for account ${account.id}" }
                    }
                }
            }
    }
}

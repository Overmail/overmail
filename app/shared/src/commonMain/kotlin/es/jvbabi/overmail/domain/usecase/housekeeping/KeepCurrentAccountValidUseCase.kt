package es.jvbabi.overmail.domain.usecase.housekeeping

import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.Key
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class KeepCurrentAccountValidUseCase(
    private val accountRepository: AccountRepository,
    private val keyValueRepository: KeyValueRepository,
) {
    /**
     * Repairs [Key.CurrentAccount] whenever it is missing or points at an account that does not
     * exist: it becomes the first account, or is deleted when there is none. Suspends for as long
     * as it runs.
     */
    suspend operator fun invoke() {
        combine(accountRepository.getAccounts(), keyValueRepository.get(Key.CurrentAccount)) { accounts, current ->
            accounts.any { it.id == current }
        }.collect { isValid ->
            if (isValid) return@collect

            // The two flows re-emit independently, so a new account and the key pointing at it
            // can arrive in either order. Read both again before overwriting what may be right.
            val accounts = accountRepository.getAccounts().first()
            val current = keyValueRepository.get(Key.CurrentAccount).first()
            if (accounts.any { it.id == current }) return@collect

            val first = accounts.firstOrNull()
            if (first != null) keyValueRepository.set(Key.CurrentAccount, first.id)
            else if (current != null) keyValueRepository.delete(Key.CurrentAccount)
        }
    }
}

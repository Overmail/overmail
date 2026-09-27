package es.jvbabi.overmail.domain.usecase.account

import es.jvbabi.overmail.domain.repository.Key
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import kotlin.uuid.Uuid

class SetCurrentAccountUseCase(
    private val keyValueRepository: KeyValueRepository,
) {
    /**
     * Makes the account with [accountId] the one the app shows. Call it only once the account is
     * saved, or [es.jvbabi.overmail.domain.usecase.housekeeping.KeepCurrentAccountValidUseCase]
     * takes the key for dangling and points it elsewhere.
     */
    suspend operator fun invoke(accountId: Uuid) {
        keyValueRepository.set(Key.CurrentAccount, accountId)
    }
}

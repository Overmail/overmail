package es.jvbabi.overmail.domain.usecase.account

import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.Key
import es.jvbabi.overmail.domain.repository.KeyValueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class GetCurrentAccountUseCase(
    private val accountRepository: AccountRepository,
    private val keyValueRepository: KeyValueRepository,
) {
    /** The account [Key.CurrentAccount] points at, or null while there is none. */
    operator fun invoke(): Flow<OvermailAccount?> {
        return keyValueRepository.get(Key.CurrentAccount).flatMapLatest { id ->
            if (id == null) flowOf(null) else accountRepository.getById(id)
        }
    }
}

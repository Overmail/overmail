package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.LoginCode
import es.jvbabi.overmail.domain.model.LoginResult
import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface OvermailAccountRepository {
    fun getById(id: Uuid): Flow<OvermailAccount?>
    fun getAll(): Flow<List<OvermailAccount>>
    suspend fun upsert(account: OvermailAccount)
    suspend fun delete(id: Uuid)

    /**
     * Checks a sign-in code against the server it names and, if the server accepts it, redeems
     * it: the account it signs in is stored, which is what takes the app out of the onboarding
     * -- `AppViewModel` follows [getAll].
     *
     * One shot. The code is spent on the server even when nothing is stored here, so a failure
     * needs a new code and not a retry.
     */
    suspend fun redeemLoginCode(code: LoginCode): LoginResult
}

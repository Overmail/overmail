package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.DbOvermailAccount
import es.jvbabi.overmail.data.remote.OvermailApi
import es.jvbabi.overmail.domain.model.LoginCode
import es.jvbabi.overmail.domain.model.LoginResult
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.OvermailAccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

class OvermailAccountRepositoryImpl(
    private val database: OvermailDatabase,
    private val api: OvermailApi,
) : OvermailAccountRepository {
    override fun getById(id: Uuid): Flow<OvermailAccount?> {
        return database.overmailAccountDao.getById(id).map { it?.toModel() }
    }

    override fun getAll(): Flow<List<OvermailAccount>> {
        return database.overmailAccountDao.getAll().map { accounts -> accounts.map { it.toModel() } }
    }

    override suspend fun upsert(account: OvermailAccount) {
        database.overmailAccountDao.upsert(account.toDb())
    }

    override suspend fun delete(id: Uuid) {
        database.overmailAccountDao.delete(id)
    }

    override suspend fun redeemLoginCode(code: LoginCode): LoginResult {
        val result = api.redeemLoginCode(code)
        // Only a token that was actually issued is worth a row; every other case leaves the app
        // exactly as it was.
        if (result is LoginResult.Success) upsert(result.account)
        return result
    }
}

private fun DbOvermailAccount.toModel() = OvermailAccount(
    id = id,
    serverUrl = serverUrl,
    username = username,
    accessToken = accessToken,
)

private fun OvermailAccount.toDb() = DbOvermailAccount(
    id = id,
    serverUrl = serverUrl,
    username = username,
    accessToken = accessToken,
)

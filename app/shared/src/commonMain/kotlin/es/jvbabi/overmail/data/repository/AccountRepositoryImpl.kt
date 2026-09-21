package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountRepositoryImpl(
    private val database: OvermailDatabase,
): AccountRepository {

    override fun getAccounts(): Flow<List<OvermailAccount>> {
        return database.overmailAccountDao.all().map { items -> items.map { it.toModel() } }
    }
}
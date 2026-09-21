package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccounts(): Flow<List<OvermailAccount>>
}
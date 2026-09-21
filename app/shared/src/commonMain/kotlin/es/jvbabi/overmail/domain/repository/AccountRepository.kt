package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun getAccounts(): Flow<List<OvermailAccount>>
    suspend fun redeemAuthCode(homeserver: String, code: String): Result<RedeemAuthCodeResponse>
}

sealed class RedeemAuthCodeResponse {
    data class Success(
        val jwt: String,
    ): RedeemAuthCodeResponse()

    data object CodeNotFound : RedeemAuthCodeResponse()
}

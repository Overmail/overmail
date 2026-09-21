package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.OvermailAccount
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface AccountRepository {
    fun getAccounts(): Flow<List<OvermailAccount>>
    suspend fun redeemAuthCode(homeserver: String, code: String): Result<RedeemAuthCodeResponse>
    suspend fun getUserInfo(homeserver: String, token: String): Result<UserinfoResponse>

    suspend fun saveAccount(account: OvermailAccount)

    fun getById(id: Uuid): Flow<OvermailAccount?>
}

sealed class RedeemAuthCodeResponse {
    data class Success(
        val jwt: String,
    ): RedeemAuthCodeResponse()

    data object CodeNotFound : RedeemAuthCodeResponse()
}

data class UserinfoResponse(
    val id: Uuid,
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
)
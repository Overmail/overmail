package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.AccountRepository
import es.jvbabi.overmail.domain.repository.RedeemAuthCodeResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AccountRepositoryImpl(
    private val database: OvermailDatabase,
    private val httpClient: HttpClient,
): AccountRepository {

    override fun getAccounts(): Flow<List<OvermailAccount>> {
        return database.overmailAccountDao.all().map { items -> items.map { it.toModel() } }
    }

    override suspend fun redeemAuthCode(homeserver: String, code: String): Result<RedeemAuthCodeResponse> = safeRequest {
        val response = httpClient.get(URLBuilder(urlString = homeserver).apply {
            appendPathSegments("api", "auth", "redeem")
            parameters.append("code", code)
        }.build())

        // Checked first: a 404 from anything but the server is a wrong homeserver, not a used code.
        if (!response.isResponseFromBackend()) throw response.toNetworkException()
        if (response.status == HttpStatusCode.NotFound) return@safeRequest RedeemAuthCodeResponse.CodeNotFound
        if (!response.status.isSuccess()) throw response.toNetworkException()

        val body: ApiRedeemAuthCodeResponse = response.body()
        RedeemAuthCodeResponse.Success(jwt = body.jwt)
    }
}

@Serializable
private data class ApiRedeemAuthCodeResponse(@SerialName("jwt") val jwt: String)
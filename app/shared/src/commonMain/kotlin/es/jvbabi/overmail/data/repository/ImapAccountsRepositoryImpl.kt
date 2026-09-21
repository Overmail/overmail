package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.cache.CachedResource
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.DbImapAccount
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.ImapAccount
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.ImapAccountsRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

class ImapAccountsRepositoryImpl(
    private val httpClient: HttpClient,
    private val overmailDatabase: OvermailDatabase,
) : ImapAccountsRepository {
    override fun getAll(
        instantLocalEmission: Boolean,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<ImapAccount>>> = CachedResource(
        local = {
            overmailDatabase.imapAccountsDao.allForAccount(overmailAccount.id)
                .map { imapAccounts -> imapAccounts.map { it.toModel() } }
        },
        fetch = { fetchAll(overmailAccount) },
        persist = { imapAccounts -> overmailDatabase.imapAccountsDao.replaceAll(overmailAccount.id, imapAccounts) },
    ).stream(instantLocalEmission)

    private suspend fun fetchAll(overmailAccount: OvermailAccount): Result<List<DbImapAccount>> = safeRequest {
        val response = httpClient.get(URLBuilder(urlString = overmailAccount.homeserver).apply {
            appendPathSegments("api", "users", "me", "inboxes")
        }.build()) {
            bearerAuth(overmailAccount.token)
        }

        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()

        response.body<ApiInboxesResponse>().inboxes.map { inbox ->
            DbImapAccount(
                id = inbox.id,
                host = inbox.host,
                port = inbox.port,
                username = inbox.username,
                isPaused = inbox.isPaused,
                emailCount = inbox.emailCount,
                overmailAccountId = overmailAccount.id,
            )
        }
    }
}

/** `GET /api/users/me/inboxes`, `http/users/me/inboxes/getInboxes.kt`. Only what the app reads of it. */
@Serializable
private data class ApiInboxesResponse(@SerialName("inboxes") val inboxes: List<Inbox>) {
    @Serializable
    data class Inbox(
        @SerialName("id") val id: Uuid,
        @SerialName("host") val host: String,
        @SerialName("port") val port: Int,
        @SerialName("username") val username: String,
        @SerialName("is_paused") val isPaused: Boolean,
        @SerialName("email_count") val emailCount: Long,
    )
}

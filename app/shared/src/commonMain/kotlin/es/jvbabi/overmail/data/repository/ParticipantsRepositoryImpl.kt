package es.jvbabi.overmail.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import es.jvbabi.overmail.data.cache.CachedResource
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.DbParticipant
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.domain.repository.ParticipantsRepository
import es.jvbabi.overmail.utils.fuzzyContains
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.uuid.Uuid

/** How many correspondents a search asks for, with or without a query. */
private const val SEARCH_LIMIT = 30

class ParticipantsRepositoryImpl(
    private val httpClient: HttpClient,
    private val overmailDatabase: OvermailDatabase,
) : ParticipantsRepository {

    /** Decoded pictures by homeserver and path; null for one that failed, so it is not asked for again. */
    private val avatars = mutableMapOf<String, ImageBitmap?>()
    private val avatarsLock = Mutex()

    override fun search(
        query: String,
        instantLocalEmission: Boolean,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<Participant>>> = CachedResource(
        local = {
            // Matched here rather than in SQL, the way the server matches: fuzzily, on the name
            // or the address.
            overmailDatabase.participantsDao.allForAccount(overmailAccount.id).map { participants ->
                val trimmed = query.trim()
                participants.asSequence()
                    .filter {
                        trimmed.isEmpty() ||
                            it.participant.email fuzzyContains trimmed ||
                            it.participant.name?.fuzzyContains(trimmed) == true
                    }
                    .take(SEARCH_LIMIT)
                    .map { it.toModel() }
                    .toList()
            }
        },
        fetch = { fetchSearch(query, overmailAccount) },
        persist = { participants -> overmailDatabase.participantsDao.upsert(participants) },
    ).stream(instantLocalEmission)

    override fun getByIds(ids: List<Uuid>, overmailAccount: OvermailAccount): Flow<List<Participant>> =
        overmailDatabase.participantsDao.byIds(overmailAccount.id, ids).map { participants -> participants.map { it.toModel() } }

    override suspend fun loadAvatar(participant: Participant): ImageBitmap? {
        val path = participant.avatarUrl ?: return null
        val account = participant.overmailAccount
        val key = account.homeserver + path

        avatarsLock.withLock { if (key in avatars) return avatars[key] }

        // The picture sits behind the session like everything else, so it goes through this
        // client rather than an image loader of its own.
        val bitmap = safeRequest {
            val response = httpClient.get(URLBuilder(urlString = account.homeserver).apply {
                appendPathSegments(path.trimStart('/').split('/'))
            }.build()) {
                bearerAuth(account.token)
            }
            if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()
            response.readRawBytes().decodeToImageBitmap()
        }.getOrNull()

        avatarsLock.withLock { avatars[key] = bitmap }
        return bitmap
    }

    private suspend fun fetchSearch(query: String, overmailAccount: OvermailAccount): Result<List<DbParticipant>> = safeRequest {
        val response = httpClient.get(URLBuilder(urlString = overmailAccount.homeserver).apply {
            appendPathSegments("api", "senders", "search")
            parameters.append("query", query)
            parameters.append("limit", SEARCH_LIMIT.toString())
        }.build()) {
            bearerAuth(overmailAccount.token)
        }

        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()

        response.body<ApiSenderSearchResponse>().senders.map { sender ->
            DbParticipant(
                id = sender.id,
                name = sender.name,
                email = sender.address,
                avatarUrl = sender.avatarUrl,
                avatarPadding = sender.avatarPadding,
                emailCount = sender.emailCount,
                overmailAccountId = overmailAccount.id,
            )
        }
    }
}

/** `GET /api/senders/search`, `http/senders/search/senderSearch.kt`. */
@Serializable
private data class ApiSenderSearchResponse(@SerialName("senders") val senders: List<Sender>) {
    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        @SerialName("avatar_padding") val avatarPadding: Double?,
        @SerialName("email_count") val emailCount: Long,
    )
}

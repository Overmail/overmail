package es.jvbabi.overmail.data.repository

import androidx.compose.ui.graphics.Color
import es.jvbabi.overmail.data.cache.CachedResource
import es.jvbabi.overmail.data.database.OvermailDatabase
import es.jvbabi.overmail.data.database.entity.DbLabels
import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.CacheableResource
import es.jvbabi.overmail.domain.model.Label
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.repository.LabelsRepository
import es.jvbabi.overmail.utils.fuzzyContains
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How many labels a search asks for, with or without a query. */
private const val SEARCH_LIMIT = 30

class LabelsRepositoryImpl(
    private val httpClient: HttpClient,
    private val overmailDatabase: OvermailDatabase,
) : LabelsRepository {
    override fun search(
        query: String,
        instantLocalEmission: Boolean,
        overmailAccount: OvermailAccount,
    ): Flow<CacheableResource<List<Label>>> = CachedResource(
        local = {
            // Matched here rather than in SQL: the server matches fuzzily, and a LIKE would throw
            // out what it found.
            overmailDatabase.labelsDao.allForAccount(overmailAccount.id).map { labels ->
                labels.asSequence()
                    .filter { query.isBlank() || it.label.name fuzzyContains query.trim() }
                    .take(SEARCH_LIMIT)
                    .map { it.toModel() }
                    .toList()
            }
        },
        fetch = { fetchSearch(query, overmailAccount) },
        persist = { labels -> overmailDatabase.labelsDao.upsert(labels) },
    ).stream(instantLocalEmission)

    override fun getByIds(ids: List<Uuid>, overmailAccount: OvermailAccount): Flow<List<Label>> =
        overmailDatabase.labelsDao.byIds(overmailAccount.id, ids).map { labels -> labels.map { it.toModel() } }

    private suspend fun fetchSearch(query: String, overmailAccount: OvermailAccount): Result<List<DbLabels>> = safeRequest {
        val response = httpClient.get(URLBuilder(urlString = overmailAccount.homeserver).apply {
            appendPathSegments("api", "labels", "search")
            parameters.append("query", query)
            parameters.append("limit", SEARCH_LIMIT.toString())
        }.build()) {
            bearerAuth(overmailAccount.token)
        }

        if (!response.isResponseFromBackend() || !response.status.isSuccess()) throw response.toNetworkException()

        response.body<ApiLabelSearchResponse>().labels.map { label ->
            DbLabels(
                id = label.id,
                name = label.name,
                description = label.description,
                color = parseHexColor(label.color),
                createdAt = Instant.fromEpochSeconds(label.createdAt),
                createdByAgent = label.createdByAgent,
                emailCount = label.emailCount,
                overmailAccountId = overmailAccount.id,
            )
        }
    }
}

/** `#RRGGBB`, the only form the server stores a label color in. */
private fun parseHexColor(hex: String): Color = Color(0xFF000000 or hex.removePrefix("#").toLong(16))

/** `GET /api/labels/search`, `http/labels/search/labelSearch.kt`. */
@Serializable
private data class ApiLabelSearchResponse(@SerialName("labels") val labels: List<Label>) {
    @Serializable
    data class Label(
        @SerialName("id") val id: Uuid,
        @SerialName("name") val name: String,
        @SerialName("color") val color: String,
        @SerialName("description") val description: String?,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("created_by_agent") val createdByAgent: Boolean,
        @SerialName("email_count") val emailCount: Long,
    )
}

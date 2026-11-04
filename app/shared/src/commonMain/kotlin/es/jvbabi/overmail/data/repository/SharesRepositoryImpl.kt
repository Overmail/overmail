package es.jvbabi.overmail.data.repository

import es.jvbabi.overmail.data.network.isResponseFromBackend
import es.jvbabi.overmail.data.network.safeRequest
import es.jvbabi.overmail.data.network.toNetworkException
import es.jvbabi.overmail.domain.model.OvermailAccount
import es.jvbabi.overmail.domain.model.Share
import es.jvbabi.overmail.domain.model.ShareDraft
import es.jvbabi.overmail.domain.repository.SharesRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SharesRepositoryImpl(private val httpClient: HttpClient) : SharesRepository {
    override suspend fun list(emailId: Uuid, account: OvermailAccount): Result<List<Share>> = safeRequest {
        val response = httpClient.get(sharesUrl(account, emailId)) { bearerAuth(account.token) }
        response.requireSuccess()
        response.body<ApiSharesResponse>().shares.map { it.toModel() }
    }

    override suspend fun create(emailId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share> = safeRequest {
        val response = httpClient.post(sharesUrl(account, emailId)) {
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(
                ApiNewShareRequest(
                    shareName = draft.name,
                    includeLabels = draft.includeLabels,
                    includeAttachments = draft.includeAttachments,
                    validUntil = draft.validUntil?.epochSeconds,
                    password = draft.password,
                    allowMetadataWithoutPassword = draft.allowMetadataWithoutPassword,
                )
            )
        }
        response.requireSuccess()
        response.body<ApiShare>().toModel()
    }

    override suspend fun update(emailId: Uuid, shareId: Uuid, draft: ShareDraft, account: OvermailAccount): Result<Share> = safeRequest {
        val response = httpClient.put(sharesUrl(account, emailId, shareId)) {
            bearerAuth(account.token)
            contentType(ContentType.Application.Json)
            setBody(
                ApiUpdateShareRequest(
                    shareName = draft.name,
                    includeLabels = draft.includeLabels,
                    includeAttachments = draft.includeAttachments,
                    validUntil = draft.validUntil?.epochSeconds,
                    password = draft.password,
                    removePassword = draft.removePassword,
                    allowMetadataWithoutPassword = draft.allowMetadataWithoutPassword,
                )
            )
        }
        response.requireSuccess()
        response.body<ApiShare>().toModel()
    }

    override suspend fun delete(emailId: Uuid, shareId: Uuid, account: OvermailAccount): Result<Unit> = safeRequest {
        val response = httpClient.delete(sharesUrl(account, emailId, shareId)) { bearerAuth(account.token) }
        response.requireSuccess()
    }
}

private fun sharesUrl(account: OvermailAccount, emailId: Uuid, shareId: Uuid? = null): Url =
    URLBuilder(urlString = account.homeserver).apply {
        appendPathSegments("api", "emails", emailId.toString(), "shares")
        if (shareId != null) appendPathSegments(shareId.toString())
    }.build()

private suspend fun HttpResponse.requireSuccess() {
    if (!isResponseFromBackend() || !status.isSuccess()) throw toNetworkException()
}

/** `SharePayload`, `http/email/item/shares/sharePayload.kt`. Timestamps in whole seconds. */
@Serializable
private data class ApiShare(
    @SerialName("id") val id: Uuid,
    @SerialName("share_name") val shareName: String? = null,
    @SerialName("shared_at") val sharedAt: Long,
    @SerialName("valid_until") val validUntil: Long? = null,
    @SerialName("include_labels") val includeLabels: Boolean,
    @SerialName("include_attachments") val includeAttachments: Boolean,
    @SerialName("has_password") val hasPassword: Boolean,
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean,
) {
    fun toModel() = Share(
        id = id,
        name = shareName,
        sharedAt = Instant.fromEpochSeconds(sharedAt),
        validUntil = validUntil?.let(Instant::fromEpochSeconds),
        includeLabels = includeLabels,
        includeAttachments = includeAttachments,
        hasPassword = hasPassword,
        allowMetadataWithoutPassword = allowMetadataWithoutPassword,
    )
}

/** `GET /api/emails/{emailId}/shares`, `getShares.kt`. */
@Serializable
private data class ApiSharesResponse(@SerialName("shares") val shares: List<ApiShare>)

/** `POST /api/emails/{emailId}/shares`, `newShare.kt`. */
@Serializable
private data class ApiNewShareRequest(
    @SerialName("share_name") val shareName: String?,
    @SerialName("include_labels") val includeLabels: Boolean,
    @SerialName("include_attachments") val includeAttachments: Boolean,
    @SerialName("valid_until") val validUntil: Long?,
    @SerialName("password") val password: String?,
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean,
)

/** `PUT /api/emails/{emailId}/shares/{shareId}`, `updateShare.kt`. */
@Serializable
private data class ApiUpdateShareRequest(
    @SerialName("share_name") val shareName: String?,
    @SerialName("include_labels") val includeLabels: Boolean,
    @SerialName("include_attachments") val includeAttachments: Boolean,
    @SerialName("valid_until") val validUntil: Long?,
    @SerialName("password") val password: String?,
    @SerialName("remove_password") val removePassword: Boolean,
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean,
)

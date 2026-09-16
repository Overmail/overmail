package es.jvbabi.overmail.server.http.share

import es.jvbabi.overmail.server.data.share.SharePassword
import es.jvbabi.overmail.server.database.models.Attachments
import es.jvbabi.overmail.server.http.api.ApiErrorCode
import es.jvbabi.overmail.server.http.api.ApiException
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * One attachment of a shared mail: `POST /api/shares/{shareId}/attachments/{attachmentId}`.
 *
 * POST rather than GET, like `openShare`: the password travels in the body, not in a url that
 * ends up in logs and the browser history.
 *
 * A share made without attachments answers 404 for all of them, the same as for an id that is
 * not this mail's.
 */
fun Route.downloadSharedAttachment() {
    post {
        val share = call.requireLiveShareFromUrl()
        val request = call.receive<DownloadSharedAttachmentRequest>()

        val hash = share.passwordHash
        val opened = hash == null || (request.password != null && SharePassword.verify(request.password, hash))
        if (!opened) {
            throw ApiException(
                status = HttpStatusCode.Forbidden,
                code = ApiErrorCode.FORBIDDEN,
                message = "That is not the password of this share",
                details = mapOf("resource" to "share"),
            )
        }

        // Spelled out rather than through idFromUrl, see the openapi plugin note in http/api/.
        val rawId = call.parameters["attachmentId"]
        val attachmentId = rawId?.let { Uuid.parseOrNull(it) } ?: notFound("attachment", rawId)
        if (!share.includeAttachments) notFound("attachment", attachmentId.toString())

        val attachment = call.database().query {
            Attachments
                .select(Attachments.filename, Attachments.contentType, Attachments.data)
                .where { (Attachments.id eq attachmentId) and (Attachments.email eq share.emailId) }
                .singleOrNull()
                ?.let { Triple(it[Attachments.filename], it[Attachments.contentType], it[Attachments.data].bytes) }
        } ?: notFound("attachment", attachmentId.toString())
        val (fileName, contentType, data) = attachment

        // Same headers as `downloadAttachment`, spelled out again for the openapi plugin.
        val asciiName = StringBuilder()
        for (character in fileName) {
            val keep = character.code in 32..126 && character != '"' && character != '\\'
            asciiName.append(if (keep) character else '_')
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            """attachment; filename="$asciiName"; filename*=UTF-8''${fileName.encodeURLParameter()}""",
        )
        call.response.header("X-Content-Type-Options", "nosniff")

        val type = runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream)
        call.respondBytes(data, type)
    }
}

@Serializable
private data class DownloadSharedAttachmentRequest(
    /** What the visitor typed to open the share. Left out for a share without a password. */
    @SerialName("password") val password: String? = null,
)

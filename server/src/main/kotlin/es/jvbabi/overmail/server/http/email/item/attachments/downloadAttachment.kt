package es.jvbabi.overmail.server.http.email.item.attachments

import es.jvbabi.overmail.server.database.models.Attachments
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * One attachment as a file: `GET /api/emails/{emailId}/attachments/{attachmentId}`.
 *
 * Always sent as a download: the bytes come from whoever wrote the mail, so a browser must not
 * render an html or svg attachment on our origin.
 */
fun Route.downloadAttachment() {
    authenticate {
        /**
         * Download an attachment of a mail.
         *
         * Description: In the type the sender declared, and always as an attachment, so a browser never renders it in place.
         *
         * Tag: Emails
         *
         * Responses:
         *   - 200 application/octet-stream The attachment
         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such mail, or no such attachment on it
         */
        get {
            val emailId = call.requireOwnedEmailIdFromUrl()
            // Spelled out rather than through idFromUrl, see the openapi plugin note in http/api/.
            val rawId = call.parameters["attachmentId"]
            val attachmentId = rawId?.let { Uuid.parseOrNull(it) } ?: notFound("attachment", rawId)

            val attachment = call.database().query {
                Attachments
                    .select(Attachments.filename, Attachments.contentType, Attachments.data)
                    .where { (Attachments.id eq attachmentId) and (Attachments.email eq emailId) }
                    .singleOrNull()
                    ?.let { Triple(it[Attachments.filename], it[Attachments.contentType], it[Attachments.data].bytes) }
            } ?: notFound("attachment", attachmentId.toString())
            val (fileName, contentType, data) = attachment

            // An ascii fallback for old clients, the real name in filename* (RFC 6266).
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
            call.response.cacheControl(
                CacheControl.MaxAge(
                maxAgeSeconds = 7.days.inWholeSeconds.toInt(),
                visibility = CacheControl.Visibility.Private,
            ))

            // The type is what the sender declared, so an unparsable one must not fail the download.
            val type = runCatching { ContentType.parse(contentType) }.getOrDefault(ContentType.Application.OctetStream)
            call.respondBytes(data, type)
        }
    }
}

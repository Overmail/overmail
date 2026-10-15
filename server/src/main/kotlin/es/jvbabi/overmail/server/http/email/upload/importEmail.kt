package es.jvbabi.overmail.server.http.email.upload

import es.jvbabi.overmail.server.database.models.ImapAccount
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.conflict
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.queryParameter
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.api.uuidQueryParameter
import es.jvbabi.overmail.server.jobs.importer.EmailImportPipeline
import es.jvbabi.overmail.server.jobs.importer.EmailInserter
import es.jvbabi.overmail.server.jobs.importer.ImportContext
import es.jvbabi.overmail.server.jobs.importer.oauthGrantStates
import es.jvbabi.overmail.server.jobs.importer.toConnection
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import kotlin.uuid.Uuid
import es.jvbabi.overmail.kamel.Email as KamelEmail

/**
 * Imports a mail from its source: `POST /api/emails/import?type=eml&target_inbox_id=...`, the
 * `.eml` file as the body.
 *
 * It takes the same way in as a mail the importer reads off the server -- [EmailImportPipeline],
 * so it is deduplicated against the inbox, gets its preview and goes to the assistant like any
 * other. It is never pushed, see [ImportContext.upload].
 *
 * The inbox decides who the mail belongs to and what it is deduplicated against; nothing is
 * written back to its IMAP server. A mail that arrives there later is recognised as this one.
 */
fun Route.importEmail() {
    authenticate {
        /**
         * Import a mail from its source.
         *
         * Description: The body is the raw RFC 5322 source; the mail is stored in the inbox, previewed and classified like an imported one.
         *
         * Tag: Emails
         *
         * Query parameters:
         *   - type [String] What the body is, only `eml` for now
         *   - target_inbox_id [kotlin.uuid.Uuid] The inbox the mail goes into
         *
         * Body: message/rfc822 The source, as an `.eml` file holds it
         *
         * Responses:
         *   - 201 [ImportedEmailResponse] The mail is stored
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A parameter is missing or unknown, or the body is no mail that can be stored
         *   - 404 [es.jvbabi.overmail.server.http.api.ApiErrorBody] No such inbox, or not one of the current user's
         *   - 409 [es.jvbabi.overmail.server.http.api.ApiErrorBody] The inbox holds this mail already
         */
        post {
            val userId = call.requireAuthenticatedUserId()

            val type = call.queryParameter("type") ?: invalidRequest("type", "is required")
            if (type != "eml") invalidRequest("type", "is not one of: eml", type)
            val inboxId = call.uuidQueryParameter("target_inbox_id")
                ?: invalidRequest("target_inbox_id", "is required")

            // A snapshot, like the importer's: the pipeline runs outside this transaction.
            val account = call.database().query {
                ImapAccount
                    .find { (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }
                    .firstOrNull()
                    ?.toConnection(oauthGrantStates(inboxId)[inboxId])
            } ?: notFound("inbox", inboxId.toString())

            val source = call.receive<ByteArray>()
            if (source.isEmpty()) invalidRequest("body", "is empty")
            val mail = try {
                KamelEmail.parse(source)
            } catch (_: IllegalArgumentException) {
                invalidRequest("body", "is not a mail")
            }

            when (val result = call.dependency<EmailImportPipeline>().import(mail, ImportContext.upload(account))) {
                is EmailInserter.Result.Imported ->
                    call.respond(HttpStatusCode.Created, ImportedEmailResponse(id = result.email.id.value))
                is EmailInserter.Result.Rejected -> invalidRequest("body", result.reason)
                EmailInserter.Result.AlreadyExists ->
                    conflict("The inbox holds this mail already", mapOf("inbox" to inboxId.toString()))
            }
        }
    }
}

@Serializable
data class ImportedEmailResponse(
    @SerialName("id") val id: Uuid,
)

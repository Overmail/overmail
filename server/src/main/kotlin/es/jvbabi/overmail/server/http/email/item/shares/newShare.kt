package es.jvbabi.overmail.server.http.email.item.shares

import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Hands one mail out to somebody who has no account here: `POST /api/emails/{emailId}/shares`.
 *
 * The answer is the whole share, [SharePayload.id] included -- that id is what the link is built
 * from, and the rest is what the owner's list of links shows. A share that has run out is not
 * deleted, so a link that stopped working can be told apart from one that never was.
 */
fun Route.newShare() {
    authenticate {
        /**
         * Share a mail through a link.
         *
         * Description: The `id` of the answer is what the link is built from.
         *
         * Tag: Shares
         *
         * Body: [NewShareRequest] The share
         *
         * Responses:
         *   - 201 [SharePayload] The new share
         *   - 400 [es.jvbabi.overmail.server.http.api.ApiErrorBody] A name that is too long, a password shorter than 4 characters, or a `valid_until` in the past
         */
        post {
            val emailId = call.requireOwnedEmailIdFromUrl()
            val request = call.receive<NewShareRequest>()

            val input = readShareInput(
                shareName = request.shareName,
                includeLabels = request.includeLabels,
                includeAttachments = request.includeAttachments,
                validUntil = request.validUntil,
                allowMetadataWithoutPassword = request.allowMetadataWithoutPassword,
            )
            val password = hashSharePassword(request.password)
            val now = Clock.System.now()

            val share = call.database().query {
                val id = Shares.insertAndGetId {
                    it[email] = emailId
                    it[sharedAt] = now
                    it[shareName] = input.shareName
                    it[includeLabels] = input.includeLabels
                    it[includeAttachments] = input.includeAttachments
                    it[validUntil] = input.validUntil
                    it[passwordHash] = password
                    it[allowMetadataWithoutPassword] = input.allowMetadataWithoutPassword
                }

                Shares.selectAll().where { Shares.id eq id }.single().toSharePayload()
            }

            call.respond(HttpStatusCode.Created, share)
        }
    }
}

@Serializable
private data class NewShareRequest(
    @JsonSchema.Description("What the owner calls the share, to tell their links apart; optional")
    @SerialName("share_name") val shareName: String? = null,
    @SerialName("include_labels") val includeLabels: Boolean = false,
    @SerialName("include_attachments") val includeAttachments: Boolean = false,
    @JsonSchema.Description("When the link stops working, in whole seconds since the epoch; null for never")
    @SerialName("valid_until") val validUntil: Long? = null,
    @JsonSchema.Description("What a visitor has to type, in the clear; null or blank for none")
    @SerialName("password") val password: String? = null,
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean = false,
)

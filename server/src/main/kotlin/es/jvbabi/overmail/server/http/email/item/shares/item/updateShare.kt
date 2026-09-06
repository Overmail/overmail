package es.jvbabi.overmail.server.http.email.item.shares.item

import es.jvbabi.overmail.server.database.models.Shares
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireOwnedEmailIdFromUrl
import es.jvbabi.overmail.server.http.email.item.shares.hashSharePassword
import es.jvbabi.overmail.server.http.email.item.shares.readShareInput
import es.jvbabi.overmail.server.http.email.item.shares.toSharePayload
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Changes a link that is already out: `PUT /api/emails/{emailId}/shares/{shareId}`.
 *
 * The whole share is sent, not a change to it, like the knowledge routes -- with the password as
 * the exception it has to be: the screen cannot send back what it never had. So [password] set is
 * a new one, [removePassword] takes the old one off, and neither leaves it as it stands.
 *
 * The id stays, so a link that was handed out keeps working under a new name, a new date or a new
 * password. Taking it back for good is `DELETE`.
 *
 * A share that is not this mail's -- or a mail that is not the caller's -- is a 404 rather than a
 * 403: it says nothing about whose share the id is.
 */
fun Route.updateShare() {
    authenticate {
        put {
            val emailId = call.requireOwnedEmailIdFromUrl()
            val shareId = shareIdFromPath(call.parameters["shareId"])
            val request = call.receive<UpdateShareRequest>()

            val input = readShareInput(
                shareName = request.shareName,
                includeLabels = request.includeLabels,
                validUntil = request.validUntil,
                allowMetadataWithoutPassword = request.allowMetadataWithoutPassword,
            )
            // Hashed before the transaction opens: this is deliberately slow work, and a
            // transaction held open across it is one connection nothing else can use.
            val newPassword = hashSharePassword(request.password)

            val share = call.database().query {
                val row = Shares
                    .selectAll()
                    .where { (Shares.id eq shareId) and (Shares.email eq emailId) }
                    .singleOrNull()
                    ?: notFound("share", shareId.toString())

                Shares.update({ Shares.id eq shareId }) {
                    it[shareName] = input.shareName
                    it[includeLabels] = input.includeLabels
                    it[validUntil] = input.validUntil
                    it[allowMetadataWithoutPassword] = input.allowMetadataWithoutPassword
                    it[passwordHash] = when {
                        newPassword != null -> newPassword
                        request.removePassword -> null
                        // Untouched: the screen showed that there is one, not what it is.
                        else -> row[Shares.passwordHash]
                    }
                }

                Shares.selectAll().where { Shares.id eq shareId }.single().toSharePayload()
            }

            call.respond(HttpStatusCode.OK, share)
        }
    }
}

@Serializable
private data class UpdateShareRequest(
    @SerialName("share_name") val shareName: String? = null,
    @SerialName("include_labels") val includeLabels: Boolean = false,
    /** When the link stops working, as whole seconds since the epoch; null never runs out. */
    @SerialName("valid_until") val validUntil: Long? = null,
    /** A password to set from now on. Null leaves the one that is there, see [removePassword]. */
    @SerialName("password") val password: String? = null,
    /** Takes the password off, so the link opens without one. Ignored when [password] is set. */
    @SerialName("remove_password") val removePassword: Boolean = false,
    @SerialName("allow_metadata_without_password") val allowMetadataWithoutPassword: Boolean = false,
)

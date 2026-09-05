package es.jvbabi.overmail.server.http.email

import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requestedIds
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/**
 * What the mails behind a list of ids are called: `GET /api/emails?ids=a,b,c`.
 *
 * The counterpart of a client-side cache -- it asks for the ids it does not know yet and gets
 * back what exists and belongs to the signed-in user. Everything else is silently missing from
 * the answer rather than an error: a lookup is not addressed at one mail, and a single unknown id
 * must not cost the caller the rest.
 */
fun Route.emailsByIds() {
    authenticate {
        get {
            val ids = call.requestedIds()
            if (ids.isEmpty()) return@get call.respond(EmailsResponse(emptyList()))

            val userId = call.requireAuthenticatedUserId()

            val emails = call.database().query {
                // Columns, not the entity: loading an Email reads its raw source with it.
                Emails
                    .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
                    .join(EmailUsers, JoinType.INNER, Emails.sender, EmailUsers.id)
                    .leftJoin(EmailAvatars)
                    .select(
                        Emails.id,
                        Emails.subject,
                        Emails.senderName,
                        Emails.sent,
                        Emails.isRead,
                        EmailUsers.id,
                        EmailUsers.address,
                        EmailUsers.avatar,
                        EmailAvatars.circlePadding,
                    )
                    .where { (Emails.id inList ids) and (ImapAccounts.user eq userId) }
                    .map { row ->
                        EmailsResponse.Email(
                            id = row[Emails.id].value,
                            subject = row[Emails.subject],
                            senderId = row[EmailUsers.id].value,
                            senderName = row[Emails.senderName],
                            senderAddress = row[EmailUsers.address],
                            avatarUrl = row.avatarUrlOrNull(),
                            avatarPadding = row.avatarPadding(),
                            sent = row[Emails.sent].epochSeconds,
                            isRead = row[Emails.isRead],
                        )
                    }
            }

            call.respond(EmailsResponse(emails))
        }
    }
}

@Serializable
private data class EmailsResponse(
    @SerialName("emails") val emails: List<Email>,
) {
    @Serializable
    data class Email(
        @SerialName("id") val id: Uuid,
        @SerialName("subject") val subject: String,
        @SerialName("sender_id") val senderId: Uuid,
        /** Display name from this mail's header, absent for a bare address. */
        @SerialName("sender_name") val senderName: String?,
        @SerialName("sender_address") val senderAddress: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        /** Whether that picture may be clipped to a circle, see `EmailAvatars.circlePadding`. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
        @SerialName("sent") val sent: Long,
        @SerialName("is_read") val isRead: Boolean,
    )
}

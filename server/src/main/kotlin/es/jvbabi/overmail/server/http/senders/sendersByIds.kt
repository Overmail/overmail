package es.jvbabi.overmail.server.http.senders

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.avatar.avatarPadding
import es.jvbabi.overmail.server.http.avatar.avatarUrlOrNull
import es.jvbabi.overmail.server.http.entities.requestedIds
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * Who the addresses behind a list of ids are: `GET /api/senders?ids=a,b,c`.
 *
 * Address rows are per user (see [EmailUsers]), so the ownership check is on the row itself; an
 * id of somebody else is missing from the answer like an unknown one.
 */
fun Route.sendersByIds() {
    authenticate {
        get {
            val ids = call.requestedIds()
            if (ids.isEmpty()) return@get call.respond(SendersResponse(emptyList()))

            val database = call.application.dependencies.resolve<OvermailDatabase>()
            val userId = call.user.id.value

            val senders = database.query {
                val rows = EmailUsers
                    .leftJoin(EmailAvatars)
                    .select(EmailUsers.id, EmailUsers.address, EmailUsers.avatar, EmailAvatars.circlePadding)
                    .where { (EmailUsers.id inList ids) and (EmailUsers.user eq userId) }
                    .toList()

                // The name comes from the mails, not from the address row: one address is used
                // with many names, and the newest one is what the user last saw.
                val names = if (rows.isEmpty()) emptyMap() else Emails
                    .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
                    .select(Emails.sender, Emails.senderName, Emails.sent)
                    .where { (Emails.sender inList rows.map { row -> row[EmailUsers.id] }) and (ImapAccounts.user eq userId) }
                    .orderBy(Emails.sent, SortOrder.DESC)
                    .mapNotNull { row -> row[Emails.senderName]?.let { name -> row[Emails.sender].value to name } }
                    // First wins: the rows are newest first.
                    .toMap()

                rows.map { row ->
                    val id = row[EmailUsers.id].value
                    SendersResponse.Sender(
                        id = id,
                        name = names[id],
                        address = row[EmailUsers.address],
                        avatarUrl = row.avatarUrlOrNull(),
                        avatarPadding = row.avatarPadding(),
                    )
                }
            }

            call.respond(SendersResponse(senders))
        }
    }
}

@Serializable
private data class SendersResponse(
    @SerialName("senders") val senders: List<Sender>,
) {
    @Serializable
    data class Sender(
        @SerialName("id") val id: Uuid,
        /** Display name from their mails, absent when they only ever sent a bare address. */
        @SerialName("name") val name: String?,
        @SerialName("address") val address: String,
        @SerialName("avatar_url") val avatarUrl: String?,
        /** Whether that picture may be clipped to a circle, see `EmailAvatars.circlePadding`. */
        @SerialName("avatar_padding") val avatarPadding: Double?,
    )
}

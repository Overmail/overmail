package es.jvbabi.overmail.server.http.users.me.inboxes

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccountFolderSyncs
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The mailboxes this user has connected: `GET /api/users/me/inboxes`.
 *
 * What the settings screen lists, and what a freshly created inbox is re-read into. The password
 * is not in here and never will be -- nothing on a screen needs it, and the row is the only place
 * it belongs.
 */
fun Route.getInboxes() {
    authenticate {
        get {
            val userId = call.requireAuthenticatedUserId()

            val inboxes = call.database().query {
                val accounts = ImapAccounts
                    .select(
                        ImapAccounts.id,
                        ImapAccounts.host,
                        ImapAccounts.port,
                        ImapAccounts.username,
                        ImapAccounts.isPaused,
                    )
                    .where { ImapAccounts.user eq userId }
                    .orderBy(ImapAccounts.username to SortOrder.ASC)
                    .map { row ->
                        InboxesResponse.Inbox(
                            id = row[ImapAccounts.id].value,
                            host = row[ImapAccounts.host],
                            port = row[ImapAccounts.port],
                            username = row[ImapAccounts.username],
                            isPaused = row[ImapAccounts.isPaused],
                            folders = emptyList(),
                            emailCount = 0L,
                        )
                    }

                // One query for every account's mails rather than one per account, same as the
                // folders below: the number of queries should not depend on how many mailboxes
                // somebody has. The count is what a delete has to warn about.
                val mailsByAccount = if (accounts.isEmpty()) emptyMap() else Emails
                    .select(Emails.imapAccount, Emails.id.count())
                    .where { Emails.imapAccount inList accounts.map { it.id } }
                    .groupBy(Emails.imapAccount)
                    .associate { it[Emails.imapAccount].value to it[Emails.id.count()] }

                // One query for every account's folders rather than one per account: the list is
                // short, but the number of queries should not depend on how many mailboxes
                // somebody has.
                val foldersByAccount = if (accounts.isEmpty()) emptyMap() else ImapAccountFolderSyncs
                    .selectAll()
                    .where { ImapAccountFolderSyncs.imapAccount inList accounts.map { it.id } }
                    .orderBy(ImapAccountFolderSyncs.folder to SortOrder.ASC)
                    .groupBy({ it[ImapAccountFolderSyncs.imapAccount].value }, { it[ImapAccountFolderSyncs.folder] })

                accounts.map { inbox ->
                    inbox.copy(
                        folders = foldersByAccount[inbox.id].orEmpty(),
                        emailCount = mailsByAccount[inbox.id] ?: 0L,
                    )
                }
            }

            call.respond(InboxesResponse(inboxes))
        }
    }
}

@Serializable
private data class InboxesResponse(
    @SerialName("inboxes") val inboxes: List<Inbox>,
) {
    @Serializable
    data class Inbox(
        @SerialName("id") val id: Uuid,
        @SerialName("host") val host: String,
        @SerialName("port") val port: Int,
        /** The imap login, which for most providers is the address itself. */
        @SerialName("username") val username: String,
        /** Whether the importer for it is switched off; nothing imported is affected by that. */
        @SerialName("is_paused") val isPaused: Boolean,
        /** The folders being synced, by name, alphabetically. */
        @SerialName("folders") val folders: List<String>,
        /** How many mails were imported through it -- what deleting it would take with it. */
        @SerialName("email_count") val emailCount: Long,
    )
}

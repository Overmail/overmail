package es.jvbabi.overmail.server.http.users.me.inboxes

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
                    .select(ImapAccounts.id, ImapAccounts.host, ImapAccounts.port, ImapAccounts.username)
                    .where { ImapAccounts.user eq userId }
                    .orderBy(ImapAccounts.username to SortOrder.ASC)
                    .map { row ->
                        Triple(row[ImapAccounts.id].value, row[ImapAccounts.host] to row[ImapAccounts.port], row[ImapAccounts.username])
                    }

                // One query for every account's folders rather than one per account: the list is
                // short, but the number of queries should not depend on how many mailboxes
                // somebody has.
                val foldersByAccount = if (accounts.isEmpty()) emptyMap() else ImapAccountFolderSyncs
                    .selectAll()
                    .where { ImapAccountFolderSyncs.imapAccount inList accounts.map { it.first } }
                    .orderBy(ImapAccountFolderSyncs.folder to SortOrder.ASC)
                    .groupBy({ it[ImapAccountFolderSyncs.imapAccount].value }, { it[ImapAccountFolderSyncs.folder] })

                accounts.map { (id, hostAndPort, username) ->
                    InboxesResponse.Inbox(
                        id = id,
                        host = hostAndPort.first,
                        port = hostAndPort.second,
                        username = username,
                        folders = foldersByAccount[id].orEmpty(),
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
        /** The folders being synced, by name, alphabetically. */
        @SerialName("folders") val folders: List<String>,
    )
}

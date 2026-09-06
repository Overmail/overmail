package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The connection the edit screen is asking about: what it typed, with the stored password filled
 * in where it typed none.
 *
 * The password is the one field that cannot be pre-filled -- the server never hands it out -- so
 * an edit screen shows it empty and means "unchanged". Without this, changing which folders are
 * synced would mean re-typing a password to prove nothing.
 */
internal data class InboxCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** The id in the path, or 404. A malformed id and an unknown one are the same miss. */
internal fun inboxIdFromPath(raw: String?): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: notFound("inbox", raw)

/**
 * Resolves what to connect with, and refuses a mailbox that is not [userId]'s.
 *
 * [password] empty means "keep the stored one". Host, port and username are taken as sent, so the
 * screen can test a server or a login it has not saved yet.
 */
internal suspend fun resolveInboxCredentials(
    database: OvermailDatabase,
    userId: Uuid,
    inboxId: Uuid,
    host: String,
    port: Int,
    username: String,
    password: String,
): InboxCredentials {
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty()) invalidRequest("host", "an imap server needs a host")
    if (port !in 1..65535) invalidRequest("port", "is not a port", port.toString())
    if (username.isEmpty()) invalidRequest("username", "a login needs a username")

    val stored = database.query {
        ImapAccounts
            .select(ImapAccounts.password)
            .where { (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }
            .map { it[ImapAccounts.password] }
            .firstOrNull()
    } ?: notFound("inbox", inboxId.toString())

    return InboxCredentials(
        host = trimmedHost,
        port = port,
        username = username,
        password = password.ifEmpty { stored },
    )
}

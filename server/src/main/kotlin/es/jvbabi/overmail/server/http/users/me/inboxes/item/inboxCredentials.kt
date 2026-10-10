package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.kamel.ImapClient
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.OAuthGrants
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.oauth.OAuthTokens
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.JoinType
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
 *
 * An inbox signed in to at a provider has no password at all: it is reached the way the sign-in
 * said, whatever the screen sent, and logs in with the grant's current access token.
 */
internal data class InboxCredentials(
    val host: String,
    val port: Int,
    val username: String,
    /** What is stored for the inbox; empty for one signed in to at a provider. */
    val password: String,
    /** What a connection logs in with. */
    val auth: ImapClient.Auth,
)

/** The id in the path, or 404. A malformed id and an unknown one are the same miss. */
internal fun inboxIdFromPath(raw: String?): Uuid =
    raw?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: notFound("inbox", raw)

/**
 * Resolves what to connect with, and refuses a mailbox that is not [userId]'s.
 *
 * [password] empty means "keep the stored one". Host, port and username are taken as sent, so the
 * screen can test a server or a login it has not saved yet -- except for an inbox signed in to at a
 * provider, which stays on the connection the sign-in named.
 */
internal suspend fun resolveInboxCredentials(
    database: OvermailDatabase,
    tokens: OAuthTokens,
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
            .join(OAuthGrants, JoinType.LEFT, ImapAccounts.id, OAuthGrants.imapAccount)
            .select(ImapAccounts.host, ImapAccounts.port, ImapAccounts.username, ImapAccounts.password, OAuthGrants.id)
            .where { (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }
            .firstOrNull()
    } ?: notFound("inbox", inboxId.toString())

    val grantId = stored.getOrNull(OAuthGrants.id)?.value
    if (grantId != null) {
        val address = stored[ImapAccounts.username]
        val bearer = tokens.accessToken(grantId) ?: notFound("inbox", inboxId.toString())
        return InboxCredentials(
            host = stored[ImapAccounts.host],
            port = stored[ImapAccounts.port],
            username = address,
            password = stored[ImapAccounts.password],
            auth = ImapClient.Auth.BearerAuth(address, bearer),
        )
    }

    val resolvedPassword = password.ifEmpty { stored[ImapAccounts.password] }
    return InboxCredentials(
        host = trimmedHost,
        port = port,
        username = username,
        password = resolvedPassword,
        auth = ImapClient.Auth.BasicAuth(username, resolvedPassword),
    )
}

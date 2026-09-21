package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.database.models.User
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * Issues a session token and records it in [Sessions], so it can be listed and revoked later.
 * Every token handed out goes through here; [JwtService.issue] on its own leaves no trace.
 */
suspend fun JwtService.issueSession(database: OvermailDatabase, userId: Uuid, client: Session.Client): String {
    val token = issue(userId, SESSION_VALIDITY)
    database.query {
        Sessions.insert {
            it[Sessions.user] = userId
            it[Sessions.client] = client
            it[Sessions.token] = token
        }
    }
    return token
}

/**
 * The user of a token [JwtService] has already accepted, or null when the account is gone or the
 * session was revoked.
 *
 * A genuine token without a row was issued before sessions were recorded. It is adopted with an
 * unknown client instead of being rejected, so nobody is signed out by the table appearing.
 */
internal suspend fun OvermailDatabase.sessionUser(token: String, userId: Uuid): User? = query {
    val user = User.findById(userId) ?: return@query null
    val session = Sessions.select(Sessions.revokedAt).where { Sessions.token eq token }.firstOrNull()

    when {
        session == null -> Sessions.insertIgnore {
            it[Sessions.user] = userId
            it[Sessions.client] = Session.Client.Web(Session.Client.UNKNOWN, Session.Client.UNKNOWN, Session.Client.UNKNOWN)
            it[Sessions.token] = token
        }
        session[Sessions.revokedAt] != null -> return@query null
    }
    user
}

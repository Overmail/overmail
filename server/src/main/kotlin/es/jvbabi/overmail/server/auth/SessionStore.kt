package es.jvbabi.overmail.server.auth

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Session
import es.jvbabi.overmail.server.database.models.Sessions
import es.jvbabi.overmail.server.database.models.User
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
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
internal suspend fun OvermailDatabase.sessionUser(token: String, userId: Uuid): User? {
    val (user, session) = query {
        val user = User.findById(userId) ?: return@query null to null
        user to Sessions.select(Sessions.revokedAt).where { Sessions.token eq token }.firstOrNull()
    }
    if (user == null) return null
    if (session != null) return user.takeIf { session[Sessions.revokedAt] == null }

    // A transaction of its own: a parallel request of the same client may insert the row first, and
    // Postgres would not let the losing transaction go on after the conflict. Either row is the same
    // unrevoked session, so losing the race is fine.
    try {
        query {
            Sessions.insert {
                it[Sessions.user] = userId
                it[Sessions.client] = Session.Client.Web(Session.Client.UNKNOWN, Session.Client.UNKNOWN, Session.Client.UNKNOWN)
                it[Sessions.token] = token
            }
        }
    } catch (e: ExposedSQLException) {
        if (e.sqlState != UNIQUE_VIOLATION) throw e
    }
    return user
}

/** The SQLSTATE of a unique index violation, the same in Postgres and H2. */
private const val UNIQUE_VIOLATION = "23505"

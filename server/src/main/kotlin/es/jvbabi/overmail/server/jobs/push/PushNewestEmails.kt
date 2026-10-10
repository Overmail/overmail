package es.jvbabi.overmail.server.jobs.push

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Pushes every user their newest mail, read or not.
 *
 * For now, while push is being built: run when the server comes up, it is the quickest way to see
 * a mail's notification without waiting for a mail. Not for a server anybody else uses.
 */
suspend fun pushNewestEmails(database: OvermailDatabase, pushNotifications: PushNotifications) {
    val newest = database.query {
        Users.select(Users.id).mapNotNull { user ->
            Emails
                .join(ImapAccounts, JoinType.INNER, Emails.imapAccount, ImapAccounts.id)
                .select(Emails.id, Emails.imapAccount)
                .where { ImapAccounts.user eq user[Users.id] }
                .orderBy(Emails.sent to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { Triple(user[Users.id].value, it[Emails.id].value, it[Emails.imapAccount].value) }
        }
    }

    newest.forEach { (userId, emailId, imapAccountId) -> pushNotifications.newEmail(userId, emailId, imapAccountId) }
}

package es.jvbabi.overmail.server.http.email.bulk

import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.dependency
import es.jvbabi.overmail.server.http.api.invalidRequest
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Whether the reader has seen a whole selection: `POST /api/emails/bulk/read` and `/bulk/unread`,
 * with `{"ids": [...]}`.
 *
 * What `/api/emails/{emailId}/read` does to one mail, for as many as one request may carry -- a
 * reader picks a day of mail and marks it read, and that is one round trip and one transaction
 * rather than one per mail.
 *
 * Which mails are the caller's is settled in the same query that finds the ones that are not in
 * the state already, so a stranger's id changes nothing and is not reported either: a selection
 * is not a place to probe with.
 */
fun Route.setEmailsRead(isRead: Boolean) {
    authenticate {
        post {
            val mailNotifier = call.dependency<MailNotifier>()
            val userId = call.requireAuthenticatedUserId()

            val ids = call.receive<BulkEmailsRequest>().ids.distinct()
            if (ids.size > MAX_BULK_IDS) {
                invalidRequest("ids", "are more than $MAX_BULK_IDS mails", ids.size.toString())
            }

            val changed = if (ids.isEmpty()) emptyList() else call.database().query {
                // Columns rather than entities: this write touches one flag, and loading an Email
                // reads its raw source with it.
                val toChange = Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where {
                        (ImapAccounts.user eq userId) and
                                (Emails.id inList ids) and
                                (Emails.isRead neq isRead)
                    }
                    .map { row -> row[Emails.id].value }

                if (toChange.isNotEmpty()) {
                    Emails.update({ Emails.id inList toChange }) { it[Emails.isRead] = isRead }
                }

                toChange
            }

            // After the transaction committed, or a reader of the event would ask again and get
            // the mail as it was before this. Nothing moved: the mails sit where they sat, they
            // only read differently now.
            changed.forEach { emailId ->
                mailNotifier.notifyMailChanged(userId, emailId, movedListings = false)
            }

            call.respond(HttpStatusCode.OK, BulkEmailsResponse(changed = changed.size))
        }
    }
}

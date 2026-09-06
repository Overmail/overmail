package es.jvbabi.overmail.server.http.users.me.inboxes.item

import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.http.api.database
import es.jvbabi.overmail.server.http.api.notFound
import es.jvbabi.overmail.server.http.api.requireAuthenticatedUserId
import es.jvbabi.overmail.server.jobs.importer.ImporterManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Disconnects a mailbox: `DELETE /api/users/me/inboxes/{inboxId}`.
 *
 * Everything imported through it goes with it -- the mails, and everything hanging off them, all
 * by the cascades on the tables. How many that is came out with the listing, so the dialog can
 * say it before the user agrees to it; it is answered here again, over what was actually removed.
 *
 * The importer is stopped first and waited for. Deleting the row underneath a running import is
 * how a half-written mail ends up pointing at an account that no longer exists.
 *
 * A mailbox that is not this user's is a miss, not a refusal: answering 403 would confirm that
 * the id belongs to somebody.
 */
fun Route.deleteInbox() {
    authenticate {
        delete {
            val userId = call.requireAuthenticatedUserId()
            val database = call.database()
            val importerManager = call.application.dependencies.resolve<ImporterManager>()

            val rawId = call.parameters["inboxId"]
            val inboxId = rawId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: notFound("inbox", rawId)

            val owned = database.query {
                ImapAccounts
                    .select(ImapAccounts.id)
                    .where { (ImapAccounts.id eq inboxId) and (ImapAccounts.user eq userId) }
                    .count() > 0L
            }
            if (!owned) notFound("inbox", inboxId.toString())

            // Before the delete, and awaited: see the note above.
            importerManager.stop(inboxId)

            val deletedEmails = database.query {
                val mails = Emails
                    .selectAll()
                    .where { Emails.imapAccount eq inboxId }
                    .count()

                // The mails, the folder settings and everything hanging off a mail go with the
                // row; every one of those references cascades. See `Emails` and its children.
                ImapAccounts.deleteWhere { ImapAccounts.id eq inboxId }

                mails
            }

            call.respond(HttpStatusCode.OK, DeleteInboxResponse(deletedEmails = deletedEmails))
        }
    }
}

@Serializable
private data class DeleteInboxResponse(
    /** How many mails went with the mailbox. */
    @SerialName("deleted_emails") val deletedEmails: Long,
)

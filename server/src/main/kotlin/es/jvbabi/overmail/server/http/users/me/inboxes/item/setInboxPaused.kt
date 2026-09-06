package es.jvbabi.overmail.server.http.users.me.inboxes.item

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
import io.ktor.server.routing.post
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Pauses or resumes a mailbox: `POST /api/users/me/inboxes/{inboxId}/pause` and `.../resume`.
 *
 * A route per state rather than a body naming one, like the read and archive routes: they are
 * separate actions to a reader, and this keeps them separate in the api too.
 *
 * Pausing takes nothing away. Every mail already imported stays, the folder settings stay, the
 * account stays -- only the importer stops polling and watching. Which is what makes it the
 * answer for "I want this to stop" that deleting is usually mistaken for.
 *
 * The row is written before the answer goes out, and the importer is dealt with after it: stopping
 * waits for the mail currently being written (see `EmailImporter.stop`) and starting opens
 * connections, and neither is something a caller should be held on.
 */
fun Route.setInboxPaused(paused: Boolean) {
    authenticate {
        post {
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

            database.query {
                ImapAccounts.update({ ImapAccounts.id eq inboxId }) { it[isPaused] = paused }
            }

            call.respond(HttpStatusCode.OK, InboxPausedResponse(paused = paused))

            call.application.launch {
                // `reboot` reads the row and leaves a paused account stopped, so both directions
                // go through the same door.
                importerManager.reboot(inboxId)
            }
        }
    }
}

@Serializable
private data class InboxPausedResponse(
    @SerialName("paused") val paused: Boolean,
)

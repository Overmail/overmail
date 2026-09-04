package es.jvbabi.overmail.server.http.email.item.labels

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.EmailLabels
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.attachLabelToEmail
import es.jvbabi.overmail.server.http.email.ownedEmailId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * Whether a label hangs on a mail: `POST /api/emails/{emailId}/labels/{labelId}` puts it there,
 * `DELETE` takes it off again.
 *
 * The pair is the whole address of it -- `EmailLabels` is unique on (email, label) -- so no
 * assignment id appears in this api, and the label id a client already holds is all it needs.
 *
 * Both are idempotent and answer the same either way: what the caller asked for is how the mail
 * stands afterwards. Only a mail that actually changed is announced.
 */
fun Route.attachEmailLabel() {
    authenticate {
        post {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()

            val pair = call.ownedEmailAndLabel()
            if (pair == null) {
                call.respond(HttpStatusCode.NotFound, "No such mail or label")
                return@post
            }
            val (emailId, labelId) = pair

            val attached = database.query { attachLabelToEmail(emailId, labelId) }

            // A label changes what a row shows, not where it sits.
            if (attached) mailNotifier.notifyMailChanged(call.user.id.value, emailId, movedListings = false)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Route.detachEmailLabel() {
    authenticate {
        delete {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()

            val pair = call.ownedEmailAndLabel()
            if (pair == null) {
                call.respond(HttpStatusCode.NotFound, "No such mail or label")
                return@delete
            }
            val (emailId, labelId) = pair

            val detached = database.query {
                EmailLabels.deleteWhere {
                    (EmailLabels.email eq emailId) and (EmailLabels.label eq labelId)
                } > 0
            }

            if (detached) mailNotifier.notifyMailChanged(call.user.id.value, emailId, movedListings = false)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The two ids in the route, if both are the caller's. Null otherwise -- a mail of somebody else,
 * a label of somebody else and one that does not exist are not told apart, so no caller learns
 * what there is.
 */
private suspend fun ApplicationCall.ownedEmailAndLabel(): Pair<Uuid, Uuid>? {
    val emailId = ownedEmailId() ?: return null
    val labelId = Uuid.parseOrNull(parameters["labelId"] ?: "") ?: return null

    val database = application.dependencies.resolve<OvermailDatabase>()
    val ownsLabel = database.query {
        Labels
            .select(Labels.id)
            .where { (Labels.id eq labelId) and (Labels.owner eq user.id) }
            .empty()
            .not()
    }

    return if (ownsLabel) emailId to labelId else null
}

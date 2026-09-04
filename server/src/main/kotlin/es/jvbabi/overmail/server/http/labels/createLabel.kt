package es.jvbabi.overmail.server.http.labels

import es.jvbabi.overmail.server.auth.user
import es.jvbabi.overmail.server.data.notifier.MailNotifier
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Label
import es.jvbabi.overmail.server.database.models.Labels
import es.jvbabi.overmail.server.database.models.attachLabelToEmail
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** `#rrggbb`, which is what the column holds and what a stylesheet can be handed. */
private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

/**
 * A new label: `POST /api/labels`.
 *
 * A name that this user already has is not a second label of that name -- the existing one is
 * answered with instead, case-insensitively, the same rule the classification follows. That is
 * what lets a client offer "create <what was typed>" without looking first.
 *
 * Without a [CreateLabelRequest.color] the label gets the one its name derives to, like the ones
 * the agent makes: a client that has no colour picker sends none rather than inventing one.
 *
 * [CreateLabelRequest.attachToEmailIds] hangs the label on those mails in the same request. One
 * mail is the panel putting a new label on what is open; a whole list of them is a selection, and
 * it is one round trip either way. Mails that are not the caller's are left out without a word
 * about them.
 */
fun Route.createLabel() {
    authenticate {
        post {
            val database = application.dependencies.resolve<OvermailDatabase>()
            val mailNotifier = application.dependencies.resolve<MailNotifier>()
            val user = call.user

            val request = call.receive<CreateLabelRequest>()

            val name = Label.normalizeName(request.name)
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "A label needs a name")
                return@post
            }

            val color = request.color
            if (color != null && !COLOR.matches(color)) {
                call.respond(HttpStatusCode.BadRequest, "A colour is #rrggbb")
                return@post
            }

            val (label, attachedTo) = database.query {
                val existing = Label
                    .find { (Labels.owner eq user.id) and (Labels.name.lowerCase() eq name.lowercase()) }
                    .firstOrNull()

                // The stored spelling wins over the one that was sent, so a second "newsletter"
                // does not rename the "Newsletter" that is already there.
                val label = existing ?: Label.new {
                    this.name = name
                    this.color = color ?: Label.defaultColorFor(name)
                    this.owner = user
                    this.description = null
                    this.createdByAgent = false
                }

                // Which of the ids are this user's mails. One query rather than one per id, and
                // the answer is the list that is allowed to be touched at all.
                val owned = if (request.attachToEmailIds.isEmpty()) emptyList() else Emails
                    .leftJoin(ImapAccounts)
                    .select(Emails.id)
                    .where { (Emails.id inList request.attachToEmailIds) and (ImapAccounts.user eq user.id) }
                    .map { row -> row[Emails.id].value }

                val attached = owned.filter { emailId -> attachLabelToEmail(emailId, label.id.value) }

                CreateLabelResponse(id = label.id.value, name = label.name, color = label.color) to attached
            }

            // After the transaction committed, or a reader of the event would ask again and get
            // the mail as it was before this.
            attachedTo.forEach { emailId ->
                mailNotifier.notifyMailChanged(user.id.value, emailId, movedListings = false)
            }

            call.respond(HttpStatusCode.OK, label)
        }
    }
}

@Serializable
data class CreateLabelRequest(
    @SerialName("name") val name: String,
    /** Null for "pick one": see [Label.defaultColorFor]. */
    @SerialName("color") val color: String? = null,
    @SerialName("attach_to_email_ids") val attachToEmailIds: List<Uuid> = emptyList(),
)

@Serializable
data class CreateLabelResponse(
    @SerialName("id") val id: Uuid,
    @SerialName("name") val name: String,
    @SerialName("color") val color: String,
)

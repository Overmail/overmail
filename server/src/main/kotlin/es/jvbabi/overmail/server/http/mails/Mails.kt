package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.MailParticipant
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** How many mails a page holds when the caller does not say. */
private const val DEFAULT_LIMIT = 300

/**
 * The most a page can hold. Not a database limit but a response one: every mail carries its
 * recipients and tags, so a page of ten thousand would be megabytes of JSON.
 */
private const val MAX_LIMIT = 1000

/** Listing the caller's mails. */
fun Route.mails() {

    authenticate(SESSION_AUTH) {
        /**
         * The caller's mails across all their accounts, newest first, without their bodies.
         * Alongside them the number of mails the window holds, so a caller can size a list for the
         * whole of it before it has read the whole of it.
         *
         * `limit` (1..[MAX_LIMIT], [DEFAULT_LIMIT] by default) caps the page. `after` and `before`
         * cut the send times down and are both exclusive; either is an ISO-8601 instant
         * (`2026-08-22T10:15:30Z`) or whole seconds since the epoch. Paging through the mailbox
         * means handing the `sent_at` of the last mail of a page back as the cursor.
         *
         * `sort` is `desc` (newest first, the default) or `asc`. Reading from the far end of a
         * long mailbox is what `asc` is for: the oldest mails become the first page and the cursor
         * to carry along is `after`, so nobody has to walk through everything in between.
         */
        get {
            // Inside `authenticate` there is a user, or the request never got here.
            val user = call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val limit = when (val requested = call.parameters["limit"]) {
                null -> DEFAULT_LIMIT
                else -> requested.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
            }

            // Told apart from a parameter that was left out: an unparseable one is the caller's
            // mistake, and answering the whole mailbox would hide it.
            val newestFirst = when (call.parameters["sort"]) {
                null, "desc" -> true
                "asc" -> false
                else -> return@get call.respond(HttpStatusCode.BadRequest)
            }

            val after = call.parameters["after"]?.let {
                it.toInstantOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            }
            val before = call.parameters["before"]?.let {
                it.toInstantOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            }

            // Resolved per request rather than while the routes are built: reaching for the
            // repository pulls the database provider, and starting up must not wait on that.
            val emailRepository = application.dependencies.resolve<EmailRepository>()
            val page = emailRepository
                .getSummariesForUser(user, limit, after, before, newestFirst)
                .first()

            call.respond(
                MailListResponse(
                    mails = page.mails.map { it.toResponse() },
                    total = page.total,
                )
            )
        }
    }
}

/**
 * Reads a query parameter as an instant, either ISO-8601 or as whole seconds since the epoch.
 * Null when it is neither.
 */
private fun String.toInstantOrNull(): Instant? {
    toLongOrNull()?.let { return runCatching { Instant.fromEpochSeconds(it) }.getOrNull() }
    return runCatching { Instant.parse(this) }.getOrNull()
}

private fun MailSummary.toResponse() = MailResponse(
    id = id.toString(),
    subject = subject,
    sender = sender.toResponse(),
    recipients = recipients.map { it.toResponse() },
    cc = cc.map { it.toResponse() },
    bcc = bcc.map { it.toResponse() },
    sentAt = sent.toString(),
    tags = tags.map { TagResponse(id = it.tag.id.toString(), name = it.tag.name) },
)

private fun MailParticipant.toResponse() = ParticipantResponse(address = address, name = name)

/** A page of mails, as `GET /api/mails` reports it. */
@Serializable
data class MailListResponse(
    /** In the requested `sort`; shorter than the requested limit once the window runs out. */
    @SerialName("mails") val mails: List<MailResponse>,
    /**
     * Mails matching `after` and `before`, this page included and the limit and `sort` ignored.
     * Counted in the same transaction as the rows, so the two cannot disagree.
     */
    @SerialName("total") val total: Int,
)

/** One mail of the listing. */
@Serializable
data class MailResponse(
    @SerialName("id") val id: String,
    @SerialName("subject") val subject: String,
    @SerialName("sender") val sender: ParticipantResponse,
    /** The `To` field. */
    @SerialName("recipients") val recipients: List<ParticipantResponse>,
    @SerialName("cc") val cc: List<ParticipantResponse>,
    @SerialName("bcc") val bcc: List<ParticipantResponse>,
    /** ISO-8601, whole seconds, as mails are stored. */
    @SerialName("sent_at") val sentAt: String,
    @SerialName("tags") val tags: List<TagResponse>,
)

/** Someone the mail names, as it spelled them out. */
@Serializable
data class ParticipantResponse(
    @SerialName("address") val address: String,
    /** Display name from this mail, absent for a bare address. */
    @SerialName("name") val name: String?,
)

/** A tag the mail is filed under. */
@Serializable
data class TagResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

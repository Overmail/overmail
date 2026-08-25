package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.auth.SESSION_AUTH
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
import kotlin.uuid.Uuid

/** How many mails a page holds when the caller does not say. */
private const val DEFAULT_LIMIT = 300

/**
 * The most a page can hold. Not a database limit but a response one: every mail carries its
 * recipients and tags, so a page of ten thousand would be megabytes of JSON.
 */
private const val MAX_LIMIT = 1000

/**
 * The most ids one request may name. Lower than [MAX_LIMIT] because they travel in the query
 * string: a uuid is 36 characters, and a few hundred of them is already a long URL.
 */
private const val MAX_IDS = 200

/** `GET /api/mails`. */
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
         *
         * `thread` narrows the window to one matter, `ids` (comma separated, at most [MAX_IDS])
         * to a named handful, and `filed` (`true`/`false`) to the mails that sit in some thread or
         * in none.
         *
         * Spam is left out unless asked for: `spam` is `false` by default, `true` for the flagged
         * mails alone and `all` for the mailbox with them in it. A reader who filed mail as spam
         * has decided about it, so a listing that shows it again would be showing decided mail. None of the three is a stretch of the list -- a thread's mails sit wherever
         * they were sent -- which is what they are for: a caller that knows what it wants asks for
         * exactly that instead of paging there. `total` then counts what was asked for rather than
         * the mailbox.
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

            val threadId = call.parameters["thread"]?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
            }

            val ids = call.parameters["ids"]?.let { raw ->
                val named = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (named.isEmpty() || named.size > MAX_IDS) return@get call.respond(HttpStatusCode.BadRequest)
                named.map { id ->
                    runCatching { Uuid.parse(id) }.getOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                }
            }

            val filed = when (call.parameters["filed"]) {
                null -> null
                "true" -> true
                "false" -> false
                else -> return@get call.respond(HttpStatusCode.BadRequest)
            }

            val spam = when (call.parameters["spam"]) {
                null, "false" -> false
                "true" -> true
                "all" -> null
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
                .getSummariesForUser(user, limit, after, before, newestFirst, threadId, ids, filed, spam = spam)
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
 * Null when it is neither. Internal, so the cursor means the same thing on the stack's socket.
 */
internal fun String.toInstantOrNull(): Instant? {
    toLongOrNull()?.let { return runCatching { Instant.fromEpochSeconds(it) }.getOrNull() }
    return runCatching { Instant.parse(this) }.getOrNull()
}

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

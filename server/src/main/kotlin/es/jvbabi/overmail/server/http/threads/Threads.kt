package es.jvbabi.overmail.server.http.threads

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.ThreadOverview
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
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

/** The caller's threads. */
fun Route.threads() {

    authenticate(SESSION_AUTH) {
        /**
         * Every thread of the caller, the one with the newest mail first, each with the ids of its
         * mails newest first.
         *
         * The whole list rather than a page of it: a thread's mails sit wherever they were sent,
         * so which thread comes next cannot be worked out from a stretch of the mailbox. The ids
         * are what keeps that affordable -- a caller lays its list out from them and then asks
         * `GET /api/mails?ids=…` for the handful it is about to show.
         *
         * Threads with no mails left in them are left out: there is nothing to rank them by and
         * nothing to show under them.
         */
        get {
            // Inside `authenticate` there is a user, or the request never got here.
            val user = call.principal<User>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            // Resolved per request rather than while the routes are built: reaching for the
            // repository pulls the database provider, and starting up must not wait on that.
            val threadRepository = application.dependencies.resolve<ThreadRepository>()
            val threads = threadRepository.getOverviewForUser(user).first()

            call.respond(ThreadListResponse(threads = threads.map { it.toResponse() }))
        }
    }
}

private fun ThreadOverview.toResponse() = ThreadOverviewResponse(
    id = thread.id.toString(),
    title = thread.title,
    lastSentAt = lastSentAt.toString(),
    mailIds = mailIds.map { it.toString() },
)

/** Every thread of the caller, as `GET /api/threads` reports them. */
@Serializable
data class ThreadListResponse(
    /** Newest mail first. */
    @SerialName("threads") val threads: List<ThreadOverviewResponse>,
)

/** One thread, with what it holds rather than the mails themselves. */
@Serializable
data class ThreadOverviewResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    /** Send time of the newest mail in it, which is what threads are ranked by. ISO-8601. */
    @SerialName("last_sent_at") val lastSentAt: String,
    /** Its mails, newest first, in the order a list shows them. */
    @SerialName("mail_ids") val mailIds: List<String>,
)

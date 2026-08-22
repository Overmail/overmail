package es.jvbabi.overmail.server.http.ai

import es.jvbabi.overmail.server.auth.SESSION_AUTH
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.jobs.processor.AiProcessingQueue
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Operating the mail agent: what it did can be taken back from here. */
fun Route.aiProcessing() {

    authenticate(SESSION_AUTH) {
        /**
         * Stops the processing queue and throws away everything the agent filed: its tags, the
         * threads it opened, and the processing stamps that said a mail had been through.
         *
         * What a user did themselves stays -- a tag they attached, a mail they put into a thread,
         * and a tag or thread the agent invented that they have since used. This is not scoped to
         * the caller: it clears the agent's work across the whole installation.
         *
         * The queue stays down until the server is restarted. It then starts over from the oldest
         * mail, because the stamps are gone.
         */
        post("/reset") {
            // Inside `authenticate` there is a user, or the request never got here.
            call.principal<User>() ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val dependencies = call.application.dependencies
            val queue = dependencies.resolve<AiProcessingQueue>()
            val emailRepository = dependencies.resolve<EmailRepository>()
            val tagRepository = dependencies.resolve<TagRepository>()
            val threadRepository = dependencies.resolve<ThreadRepository>()

            val wasRunning = queue.isRunning
            // Down and waited for before anything is deleted: the mail the agent has in its hands
            // right now would otherwise be filed after the wipe had already gone past it.
            queue.stop()

            val tags = tagRepository.clearAgentWork()
            val threads = threadRepository.clearAgentWork()
            val mails = emailRepository.clearAiProcessing()

            call.respond(
                AgentWorkReset(
                    processorWasRunning = wasRunning,
                    removedTagLinks = tags.links,
                    removedTags = tags.created,
                    removedThreadLinks = threads.links,
                    removedThreads = threads.created,
                    unstampedMails = mails,
                )
            )
        }
    }
}

/** What `POST /api/ai/reset` threw away. */
@Serializable
data class AgentWorkReset(
    /** Whether the queue was working through the mailbox when the request came in. */
    @SerialName("processor_was_running") val processorWasRunning: Boolean,
    /** Mails the agent had filed under a tag, which are now unfiled. */
    @SerialName("removed_tag_links") val removedTagLinks: Int,
    /** Tags the agent invented and nobody has used since. */
    @SerialName("removed_tags") val removedTags: Int,
    /** Mails the agent had put into a thread, which now sit in none. */
    @SerialName("removed_thread_links") val removedThreadLinks: Int,
    /** Threads the agent opened and no mail sits in any more. */
    @SerialName("removed_threads") val removedThreads: Int,
    /** Mails that carried a processing stamp and are due again. */
    @SerialName("unstamped_mails") val unstampedMails: Int,
)

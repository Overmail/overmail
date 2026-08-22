package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What the mail agent is doing, seen from one user.
 *
 * One agent serves the whole installation and works through every mailbox in a single queue, so
 * both halves are cut to the person asking: the counts are their share of the queue, and a mail of
 * somebody else shows up as [AgentWork.Pending] rather than as work they can watch.
 */
data class AgentStatus(
    val queue: AgentQueue,
    val work: AgentWork,
)

/** How far the agent has come through one user's mailbox. */
data class AgentQueue(
    val mode: AgentQueueMode,
    /** Mails of the user the agent has been through, i.e. that carry a processing stamp. */
    val processed: Int,
    /** Mails of the user still waiting, the one being worked on right now included. */
    val queued: Int,
    /** Send time of the oldest mail still waiting, null when nothing is. */
    val oldestQueued: Instant?,
)

/** Whether what is left is a mailbox being worked through or the day's post. */
enum class AgentQueueMode {
    /** The oldest waiting mail is well behind us: a freshly imported mailbox is being caught up. */
    BACKLOG,

    /** Nothing old is waiting, so at most recent mail is being worked through. */
    LIVE,
}

/** What the agent has in its hands. */
sealed interface AgentWork {

    /** Nothing to do: the queue has run dry, or the agent is down. */
    data object Idle : AgentWork

    /**
     * Busy with a mail of somebody else, so the user's own mails wait their turn. Which mail is
     * deliberately not said: it is none of this user's business.
     */
    data object Pending : AgentWork

    /** Working on a mail of this user, which is the only case its content is handed out in. */
    data class Processing(
        val emailId: Uuid,
        val subject: String,
        val sender: MailParticipant,
        val step: AgentStep,
    ) : AgentWork
}

/**
 * Which of the passes over a mail the agent is on. A mail goes through them in this order, and
 * every one of them is a model call of its own, so this is what moves while a single mail is being
 * worked on -- see [es.jvbabi.overmail.server.jobs.processor.AiProcessingQueue].
 */
enum class AgentStep {
    /** Reading off who the mail came from. */
    ORIGIN,

    /** Suggesting what to file it under. */
    TAGS,

    /** Working out which matter it continues. */
    THREAD,

    /** Going over the filing again with the neighbouring mails in view. */
    REVIEW,
}

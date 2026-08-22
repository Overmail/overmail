package es.jvbabi.overmail.server.jobs.processor

import es.jvbabi.overmail.server.domain.models.AgentStep
import kotlin.uuid.Uuid

/**
 * The mail [AiProcessingQueue] has in its hands right now, see [AiProcessingQueue.currentWork].
 *
 * One queue serves the whole installation, so the mail need not belong to whoever is watching; the
 * owner is carried along so a reader can tell the two apart, and nothing of what the mail says is
 * handed on before that has been checked.
 */
data class ProcessingMail(
    val emailId: Uuid,
    /** Who the mail belongs to, by way of the account it was imported through. */
    val userId: Uuid,
    val subject: String,
    /** Display name the sender used in this mail, null for a bare address. */
    val senderName: String?,
    val senderAddress: String,
    /** Which pass the mail is in right now; a mail goes through several. */
    val step: AgentStep,
)

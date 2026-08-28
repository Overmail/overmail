package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One mail waiting for the agent, see
 * [es.jvbabi.overmail.server.database.models.AiProcessingQueue].
 */
data class AiQueueEntry(
    val id: Uuid,
    val emailId: Uuid,
    /** Why it is queued, and what the run will be recorded under. */
    val reason: ClassificationReason,
    val enqueuedAt: Instant,
    val attempts: Int,
    val lastError: String?,
)

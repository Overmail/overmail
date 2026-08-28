package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.AiProcessingQueue
import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toAiQueueEntry(): AiQueueEntry = AiQueueEntry(
    id = this[AiProcessingQueue.id].value,
    emailId = this[AiProcessingQueue.email].value,
    reason = this[AiProcessingQueue.reason],
    enqueuedAt = this[AiProcessingQueue.enqueuedAt],
    attempts = this[AiProcessingQueue.attempts],
    lastError = this[AiProcessingQueue.lastError],
)

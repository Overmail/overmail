package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.Memories
import es.jvbabi.overmail.server.domain.models.Memory
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toMemory(): Memory = Memory(
    id = this[Memories.id].value,
    userId = this[Memories.user].value,
    parentId = this[Memories.parent]?.value,
    topic = this[Memories.topic],
    content = this[Memories.content],
    relevantFrom = this[Memories.relevantFrom],
    relevantTo = this[Memories.relevantTo],
    learnedFromEmailId = this[Memories.learnedFrom]?.value,
    createdAt = this[Memories.createdAt],
    createdByAgent = this[Memories.createdByAgent],
)

package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.Archived
import es.jvbabi.overmail.server.domain.models.ArchiveEntry
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toArchiveEntry(): ArchiveEntry = ArchiveEntry(
    id = this[Archived.id].value,
    isArchived = this[Archived.isArchived],
    createdAt = this[Archived.createdAt],
    createdByAgent = this[Archived.createdByAgent],
)

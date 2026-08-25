package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailSpam
import es.jvbabi.overmail.server.domain.models.SpamEntry
import es.jvbabi.overmail.server.domain.models.SpamFilter
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The repository resolves the filter and hands it in, as the mappers around here do -- null for a
 * mail somebody marked by hand.
 */
fun ResultRow.toSpamEntry(filter: SpamFilter?): SpamEntry = SpamEntry(
    id = this[EmailSpam.id].value,
    isSpam = this[EmailSpam.isSpam],
    filter = filter,
    createdAt = this[EmailSpam.createdAt],
)

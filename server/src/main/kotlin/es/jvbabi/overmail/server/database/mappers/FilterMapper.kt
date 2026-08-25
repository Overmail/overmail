package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.Filters
import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.spam.SpamRule
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The rule is stored as JSON, so this is where it is read back into the tree. The repository hands
 * in the resolved [user], as the mappers around here do.
 */
fun ResultRow.toSpamFilter(user: User, json: Json): SpamFilter = SpamFilter(
    id = this[Filters.id].value,
    user = user,
    name = this[Filters.name],
    rule = json.decodeFromString<SpamRule>(this[Filters.rule]),
    isActive = this[Filters.isActive],
    createdAt = this[Filters.createdAt],
)

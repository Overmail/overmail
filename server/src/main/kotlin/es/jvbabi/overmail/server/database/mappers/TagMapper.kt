package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.User
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toTag(user: User): Tag = Tag(
    id = this[Tags.id].value,
    user = user,
    name = this[Tags.name],
    description = this[Tags.description],
    createdAt = this[Tags.createdAt],
    createdByAgent = this[Tags.createdByAgent],
)

fun ResultRow.toEmailTag(tag: Tag): EmailTag = EmailTag(
    id = this[EmailTags.id].value,
    tag = tag,
    reason = this[EmailTags.reason],
    createdAt = this[EmailTags.createdAt],
    createdByAgent = this[EmailTags.createdByAgent],
)

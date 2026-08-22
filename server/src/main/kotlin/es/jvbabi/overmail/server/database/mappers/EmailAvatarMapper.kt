package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.domain.models.EmailAvatar
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps an [EmailAvatars] row joined with the [EmailUsers] row pointing at it. [EmailAvatars.data]
 * is deliberately not read here, see [EmailAvatar].
 */
fun ResultRow.toEmailAvatar(): EmailAvatar = EmailAvatar(
    id = this[EmailAvatars.id].value,
    address = this[EmailUsers.address],
    source = this[EmailAvatars.avatarSource],
    createdAt = this[EmailAvatars.createdAt],
)

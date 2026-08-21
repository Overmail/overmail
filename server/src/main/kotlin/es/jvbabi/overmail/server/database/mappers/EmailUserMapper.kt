package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.User
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps a row of [EmailUsers] to its domain model. [user] has to be resolved by the caller,
 * either from a joined [es.jvbabi.overmail.server.database.models.Users] row via [toUser] or
 * from an already known user.
 */
fun ResultRow.toEmailUser(user: User): EmailUser = EmailUser(
    id = this[EmailUsers.id].value,
    user = user,
    address = this[EmailUsers.address],
)

package es.jvbabi.overmail.server.database.mappers

import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.User
import org.jetbrains.exposed.v1.core.ResultRow

fun ResultRow.toUser(): User = User(
    id = this[Users.id].value,
    username = this[Users.username],
    email = this[Users.email],
)

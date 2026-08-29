package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

data class ImapAccount(
    val id: Id,
    val user: User,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    typealias Id = Uuid
}

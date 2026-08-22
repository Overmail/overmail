package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

data class User(
    val id: Uuid,
    val username: String,
    val email: String,
    /** The person behind the account, as they would sign a mail. */
    val name: String,
)

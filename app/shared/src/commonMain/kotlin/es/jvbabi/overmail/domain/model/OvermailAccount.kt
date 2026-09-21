package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

data class OvermailAccount(
    val id: Uuid,
    val username: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val homeserver: String,
    val token: String,
)
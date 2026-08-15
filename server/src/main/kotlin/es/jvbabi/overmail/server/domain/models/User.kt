package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

data class User(
    val id: Uuid,
    val username: String,
    val email: String,
)

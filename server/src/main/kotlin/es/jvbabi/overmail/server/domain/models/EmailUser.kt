package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

data class EmailUser(
    val id: Uuid,
    val user: User,
    val name: String?,
    val address: String,
)

package es.jvbabi.overmail.domain.model

import kotlin.uuid.Uuid

data class OvermailAccount(
    val id: Uuid,
    val serverUrl: String,
    val username: String,
    val accessToken: String,
)

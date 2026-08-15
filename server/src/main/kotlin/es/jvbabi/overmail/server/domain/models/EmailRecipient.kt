package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

enum class EmailRecipientType { RECIPIENT, CC, BCC }

data class EmailRecipient(
    val id: Uuid,
    val emailUser: EmailUser,
    val type: EmailRecipientType,
)

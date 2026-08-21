package es.jvbabi.overmail.server.domain.models

import kotlin.uuid.Uuid

enum class EmailRecipientType { RECIPIENT, CC, BCC }

data class EmailRecipient(
    val id: Uuid,
    val emailUser: EmailUser,
    /** Display name from the header field this recipient stood in, null for a bare address. */
    val name: String?,
    val type: EmailRecipientType,
)

/** A recipient link about to be stored, i.e. an [EmailRecipient] that has no id yet. */
data class NewEmailRecipient(
    val emailUser: EmailUser,
    val name: String?,
    val type: EmailRecipientType,
)

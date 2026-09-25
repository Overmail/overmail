package es.jvbabi.overmail.domain.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class Email(
    val id: Uuid,
    val overmailAccount: OvermailAccount,
    val imapAccount: ImapAccount,
    val sentBy: Participant,
    val sentAt: Instant,
    val subject: String?,
    val isRead: Boolean,
)
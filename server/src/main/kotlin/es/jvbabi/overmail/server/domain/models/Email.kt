package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A stored mail without its raw source: that can be megabytes and would be re-read on every
 * replication tick, so load it separately via
 * [es.jvbabi.overmail.server.domain.repository.EmailRepository.getRawContent].
 */
data class Email(
    val id: Uuid,
    val imapAccount: ImapAccount,
    val sender: EmailUser,
    /** Display name the sender used in this mail, null for a bare address. */
    val senderName: String?,
    val subject: String,
    val sent: Instant,
    val textContent: String?,
    val htmlContent: String?,
    val isRead: Boolean,
    val recipients: List<EmailRecipient>,
)

/**
 * Mails are deduplicated by account, send second and subject, so send times are stored and looked
 * up at second precision.
 */
fun Instant.truncatedToSecond(): Instant = Instant.fromEpochSeconds(epochSeconds)

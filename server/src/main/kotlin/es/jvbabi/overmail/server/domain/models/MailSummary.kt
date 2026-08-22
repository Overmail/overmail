package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A mail as a listing shows it: what it was about, who it went to and how it is filed, without the
 * bodies. Those run to tens of thousands of characters per mail and a list of a hundred would read
 * them all off the wire for nothing -- load a single mail through
 * [es.jvbabi.overmail.server.domain.repository.EmailRepository.getById] when the body is wanted.
 */
data class MailSummary(
    val id: Uuid,
    val subject: String,
    val sent: Instant,
    val sender: MailParticipant,
    /** The `To` field. */
    val recipients: List<MailParticipant>,
    val cc: List<MailParticipant>,
    val bcc: List<MailParticipant>,
    val isRead: Boolean,
    /** Whether the mail sits in the archive; how it got there is not a listing's business. */
    val isArchived: Boolean,
    /**
     * The matter the mail sits in, null while nothing has filed it. A mail may sit in more than
     * one; a listing shows the first, which is enough to group by.
     */
    val thread: MailThreadRef?,
    val tags: List<EmailTag>,
)

/** The matter a mail sits in, as a listing needs it: what it is called and how big it is. */
data class MailThreadRef(
    val id: Uuid,
    val title: String,
    /** Mails the thread holds altogether, not the ones of it that are on this page. */
    val size: Int,
)

/**
 * A page of [MailSummary]s together with how much there is to page through, so a caller can size a
 * scrollbar for the whole window before it has read all of it.
 */
data class MailPage(
    val mails: List<MailSummary>,
    /** Mails matching the window the page was cut out of, this page included. */
    val total: Int,
)

/**
 * Someone a mail names, as that mail spelled them out. Address and display name only -- a listing
 * has no use for the address book row behind them, see [EmailUser].
 */
data class MailParticipant(
    val address: String,
    /** Display name from this mail's header field, null for a bare address. */
    val name: String?,
)

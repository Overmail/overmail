package es.jvbabi.overmail.server.http.mails

import es.jvbabi.overmail.server.domain.models.MailParticipant
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.MailThreadRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shapes a mail goes out in, and the mappers onto them.
 *
 * Shared rather than per route on purpose: the stack's socket sends the same mails as the listing
 * does, so a mail reads the same whichever channel a client is on.
 */

/** One mail without its body. */
@Serializable
data class MailResponse(
    @SerialName("id") val id: String,
    @SerialName("subject") val subject: String,
    @SerialName("sender") val sender: ParticipantResponse,
    /** The `To` field. */
    @SerialName("recipients") val recipients: List<ParticipantResponse>,
    @SerialName("cc") val cc: List<ParticipantResponse>,
    @SerialName("bcc") val bcc: List<ParticipantResponse>,
    /** ISO-8601, whole seconds, as mails are stored. */
    @SerialName("sent_at") val sentAt: String,
    @SerialName("is_read") val isRead: Boolean,
    /** The matter the mail sits in, absent while nothing has filed it. */
    @SerialName("thread") val thread: ThreadResponse? = null,
    @SerialName("tags") val tags: List<TagResponse>,
)

/** The matter a mail sits in. */
@Serializable
data class ThreadResponse(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    /** Mails the thread holds altogether, not the ones of it on this page. */
    @SerialName("size") val size: Int,
)

/** Someone the mail names, as it spelled them out. */
@Serializable
data class ParticipantResponse(
    @SerialName("address") val address: String,
    /** Display name from this mail, absent for a bare address. */
    @SerialName("name") val name: String?,
)

/** A tag the mail is filed under. */
@Serializable
data class TagResponse(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

/** Internal, so a mail has one wire shape whichever channel it goes out on. */
internal fun MailSummary.toResponse() = MailResponse(
    id = id.toString(),
    subject = subject,
    sender = sender.toResponse(),
    recipients = recipients.map { it.toResponse() },
    cc = cc.map { it.toResponse() },
    bcc = bcc.map { it.toResponse() },
    sentAt = sent.toString(),
    isRead = isRead,
    thread = thread?.toResponse(),
    tags = tags.map { TagResponse(id = it.tag.id.toString(), name = it.tag.name) },
)

private fun MailThreadRef.toResponse() = ThreadResponse(
    id = id.toString(),
    title = title,
    size = size,
)

private fun MailParticipant.toResponse() = ParticipantResponse(address = address, name = name)

package es.jvbabi.overmail.server.ai

import ai.koog.prompt.text.text

/** Someone on a mail: a display name if the mail carried one, plus the address. */
data class MailParticipant(
    val name: String?,
    val address: String,
) {
    override fun toString(): String = if (name.isNullOrBlank()) address else "$name <$address>"
}

/**
 * One mail as every analysis step sees it. Assembled once per mail and handed to each step
 * unchanged, so two steps can never disagree about what they were looking at.
 *
 * [owner] is whose mailbox the mail sits in, which is what lets a step tell a mail the user wrote
 * from one they received.
 */
data class MailContext(
    val owner: MailParticipant,
    val sender: MailParticipant,
    val recipients: List<MailParticipant>,
    val subject: String,
    val body: String,
) {
    /** The mail as one user message: the envelope first, so the model reads it before the prose. */
    fun asMessage(): String = text {
        textWithNewLine("Mailbox owner: $owner")
        textWithNewLine("From: $sender")
        textWithNewLine("To: ${recipients.joinToString().ifBlank { "-" }}")
        textWithNewLine("Subject: $subject")
        textWithNewLine("")
        textWithNewLine(body)
    }
}

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
 * Which side of a mail the mailbox owner is on.
 *
 * The mailbox holds the sent folder as well as the inbox, so the owner is the sender about as
 * often as they are a recipient. Left to work that out from the addresses, a model reads the
 * counterparty off the From line either way and files a mail the owner wrote under their own name.
 * So it is worked out here, once, and stated to the model outright.
 */
enum class MailDirection {
    /** Written by the owner: the other party is on the To line. */
    OUTGOING,

    /** Written to the owner: the other party is the sender. */
    INCOMING,

    /**
     * The owner's own address is neither the sender nor among the recipients -- an alias, a
     * mailing list, a mail sent from somewhere else. Only the mail itself can say who wrote to
     * whom, so the model is told as much rather than handed a guess.
     */
    UNCLEAR;

    /** The line the envelope carries, in the model's words rather than this enum's. */
    val statement: String
        get() = when (this) {
            OUTGOING -> "outgoing -- the mailbox owner wrote this mail. The other party is on the To line."
            INCOMING -> "incoming -- the mailbox owner received this mail. The other party is the sender."
            UNCLEAR -> "unclear -- the owner's own address is neither the sender nor a recipient. " +
                "Read off the mail itself who wrote to whom."
        }

    companion object {
        /** Which side the owner is on, going by the addresses. Case is not part of an address. */
        fun of(
            ownerAddress: String,
            senderAddress: String,
            recipientAddresses: List<String>,
        ): MailDirection = when {
            senderAddress.equals(ownerAddress, ignoreCase = true) -> OUTGOING
            recipientAddresses.any { it.equals(ownerAddress, ignoreCase = true) } -> INCOMING
            else -> UNCLEAR
        }
    }
}

/**
 * One mail as every analysis step sees it. Assembled once per mail and handed to each step
 * unchanged, so two steps can never disagree about what they were looking at.
 *
 * [owner] is whose mailbox the mail sits in, and [direction] which side of this mail they are on.
 */
data class MailContext(
    val owner: MailParticipant,
    val direction: MailDirection,
    val sender: MailParticipant,
    val recipients: List<MailParticipant>,
    val subject: String,
    val body: String,
) {
    /** The mail as one user message: the envelope first, so the model reads it before the prose. */
    fun asMessage(): String = text {
        textWithNewLine("Mailbox owner: $owner")
        // Above From and To, so it is read before the addresses it is about.
        textWithNewLine("Direction: ${direction.statement}")
        textWithNewLine("From: $sender")
        textWithNewLine("To: ${recipients.joinToString().ifBlank { "-" }}")
        textWithNewLine("Subject: $subject")
        textWithNewLine("")
        textWithNewLine(body)
    }
}

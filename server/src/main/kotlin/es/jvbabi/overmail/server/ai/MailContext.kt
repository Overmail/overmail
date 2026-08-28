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
    /**
     * What the mailbox knows about its owner, as short lines with a handle each -- see
     * [es.jvbabi.overmail.server.domain.agent.MemoryHandles].
     *
     * The summaries only, and only the ones that were true when this mail was sent. That is what
     * makes them affordable: a mailbox that knows forty things about somebody cannot spend its
     * context on all of them to read one mail about a parcel, and a degree finished in 2022 has no
     * business explaining a mail from this week. Whatever is known beyond these lines is fetched by
     * handle, by the one step that can ask for it.
     *
     * Background and not content. A step reads these to understand what the mail is talking about --
     * that "TU" is the reader's university, that "BeLL" is a project of theirs -- and never as
     * something the mail says: anything a step claims to have quoted is checked against the mail
     * itself, which is what keeps that line from being crossed by accident.
     */
    val memories: List<String> = emptyList(),
    /**
     * The links the mail carries, whole and numbered from 1 in the order they appear, see
     * [mailLinks]. Empty for the mail that carries none, which is most of it.
     *
     * Beside the body rather than in it, because the body has had its links cut down to their hosts
     * -- see [readableBody], where that is what makes a newsletter affordable at all. A step that
     * only reads where a mail comes from wants the host and nothing more; a step that has to hand a
     * sign-in link back needs the thing itself, and a link is only a way in while it is whole.
     *
     * Handed to every step like the rest of the context, and cheap for the mail that has one or two
     * links. The step that has no use for them ignores them, as it ignores the To line of a mail
     * about nothing.
     */
    val links: List<String> = emptyList(),
) {
    /** The mail as one user message: the envelope first, so the model reads it before the prose. */
    fun asMessage(): String = text {
        textWithNewLine("Mailbox owner: $owner")
        // Above From and To, so it is read before the addresses it is about.
        textWithNewLine("Direction: ${direction.statement}")
        textWithNewLine("From: $sender")
        textWithNewLine("To: ${recipients.joinToString().ifBlank { "-" }}")
        // A mail may arrive without a subject or without any text at all. Both are said outright
        // rather than left as an empty line: a model handed a blank reads the line below as the
        // subject, or answers that it cannot tell anything about the mail.
        textWithNewLine("Subject: ${subject.ifBlank { NO_SUBJECT }}")

        // Before the mail rather than after it: this is what the mail is read *against*, and a
        // reader who is told afterwards that the sender is their landlord has already read it wrong.
        if (memories.isNotEmpty()) {
            textWithNewLine("")
            textWithNewLine(MEMORIES_HEADING)
            memories.forEach { textWithNewLine(it) }
        }

        textWithNewLine("")
        textWithNewLine(body.ifBlank { NO_BODY })

        // After the body, not before it: this is an appendix to the mail rather than part of what
        // it says, and a model that reads it first reads a page of URLs as the mail.
        if (links.isNotEmpty()) {
            textWithNewLine("")
            textWithNewLine(LINKS_HEADING)
            links.forEachIndexed { index, link -> textWithNewLine("[${index + 1}] $link") }
        }
    }
}

/** What stands where the subject would: a mail without one is filed all the same. */
const val NO_SUBJECT = "(none -- this mail carries no subject line)"

/**
 * What introduces the numbered links. It says where they came from and that they are the same ones,
 * because in the body above they stand as bare hosts -- a model told nothing reads the list as a
 * second set of links the mail did not have.
 */
private const val LINKS_HEADING =
    "The links of this mail, whole and numbered, in the order they appear above (in the text above " +
        "each of them stands as its host only):"

/**
 * What introduces the memories. It says whose they are and, more importantly, what they are not:
 * background about the owner rather than anything this mail states.
 */
private const val MEMORIES_HEADING =
    "What is known about the mailbox owner, as it stood when this mail was sent. Background only -- " +
        "none of this is something the mail says, and nothing here may be quoted as if it were:"

/** What stands where the text would, for a mail that is only an envelope. */
private const val NO_BODY = "(none -- this mail carries no text)"

package es.jvbabi.overmail.server.ai.steps

import es.jvbabi.overmail.server.ai.NO_SUBJECT
import es.jvbabi.overmail.server.domain.models.TaggedMail

/**
 * The From line of a neighbouring mail as the steps list it, with the owner's own mails marked.
 *
 * Shared by the thread step and the review, which both list the neighbourhood and both compare who
 * wrote what: the sent folder sits in this mailbox as well, so an unmarked From line makes the
 * owner's own answer look like a mail from a second party.
 */
internal fun TaggedMail.from(): String =
    if (fromOwner) "$sender (the mailbox owner)" else sender

/**
 * The subject of a neighbouring mail as the steps list it, with a missing one spelled out.
 *
 * Left blank, the line reads as though the listing were broken, and the mail below it is the one
 * that gets compared instead.
 */
internal fun TaggedMail.subjectLine(): String = subject.ifBlank { NO_SUBJECT }

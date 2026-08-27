package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * The string a mail carries for the matter it is part of: an invoice number, an order number, the
 * number a platform gives a conversation.
 *
 * Written down for one purpose, and it is a purpose the mail itself cannot serve: a matter is only
 * a matter once a second mail turns up about it, and the second mail can only find the first one if
 * somebody wrote down what the first one said. So the identifier is recorded per mail as it is
 * read, and a thread is opened later -- when there is something to put in it, see [Threads].
 *
 * Its own table rather than a column on [Emails] for the reason [MagicEmails] is one: almost no mail
 * carries one of these, and what is asked of these rows is "which mails name this string", which is
 * a query over a small table rather than a scan of the mailbox.
 *
 * No kind here, unlike the reading it comes from. What a number identifies is worth saying while a
 * thread is being named, and the mail that names it is doing the naming -- a row that only exists to
 * be matched needs the string and nothing else.
 */
object MailIdentifiers : UuidTable("mail_identifiers") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /**
     * The identifier as the mail spells it: `RE-2024-00123`, `#INC0043221`.
     *
     * Copied and never assembled -- it is checked against the mail before it gets here, see
     * [es.jvbabi.overmail.server.ai.TopicAnalysis.threadId]. A string nobody wrote will match
     * nothing ever, which makes it the one kind of row that is worse than no row at all.
     */
    val identifier = varchar("identifier", 128)

    /** When it was read off the mail, which is not when the mail arrived. */
    val createdAt = timestamp("created_at")

    init {
        // One matter per mail: the step that reads it is told to name the one the mail is about,
        // and a mail read twice must not end up naming it twice.
        uniqueIndex(email)
    }
}

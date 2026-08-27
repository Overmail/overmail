package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/** A matter several mails belong to, scoped per [Users] row like tags and the address book. */
object Threads : UuidTable("threads") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /** What the matter is called. May be sharpened once more mails join. */
    val title = varchar("title", 255)

    /**
     * The string the matter goes by in the mail about it: an invoice number, an order number, the
     * number a platform gives a conversation.
     *
     * This is what makes a thread findable by a second mail nobody has read yet. A matter is not
     * recognised by its title -- two senders write about one order in two different words -- but an
     * order number is written the same way by everybody who has it, so a mail carrying one can be
     * put in with the mail that carried it before without anything having to compare prose.
     *
     * Null for a thread that was opened without one: a reader grouping mail by hand, or a matter
     * whose mail simply numbers nothing. Those threads are joined by being picked, not by being
     * matched, and there is nothing to write here for them.
     *
     * Copied off the mail exactly as it stood there, never assembled -- see
     * [es.jvbabi.overmail.server.ai.TopicAnalysis.threadId], which is checked against the mail
     * before it reaches this column.
     */
    val identifier = varchar("identifier", 128).nullable()

    val createdAt = timestamp("created_at")

    /** False for a thread the user opened themselves. */
    val createdByAgent = bool("created_by_agent")

    init {
        // One thread per identifier and user. Two mails carrying the same order number are the same
        // matter, and a mailbox reading them at the same moment must end up with one thread rather
        // than two halves of one. Postgres counts nulls as distinct, which is what leaves the
        // threads without an identifier alone -- a reader may open as many of those as they like.
        uniqueIndex(user, identifier)
    }
}

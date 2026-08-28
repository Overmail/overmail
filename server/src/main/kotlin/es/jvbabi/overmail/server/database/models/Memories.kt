package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * What the mailbox knows about the person whose mailbox it is.
 *
 * Two levels in one table, told apart by [parent]. A row without a parent is a core memory: one line
 * about something standing in the reader's life -- their studies, their work, a project, a club --
 * short enough that every one of them can be put in front of the model at once. A row with a parent
 * is a detail of that thing, and details are never put in front of anything until they are asked
 * for. That split is the whole point: a mailbox that knows forty things about somebody cannot spend
 * its context on all of them to read one mail about a parcel, and the summary is enough for the
 * model to notice when it is *not* enough.
 *
 * The predecessor of this was a graph -- entities with aliases, facts with predicates, life events,
 * five tables -- and it could answer questions nothing ever asked. What a step actually needs is
 * "what should I know before reading this mail", and the answer to that is a short list of lines
 * plus a way to ask for more.
 *
 * Nothing here is deleted. A memory that has stopped being true is given an end, see [relevantTo]:
 * what was true stays true about its own time, and a mail from then is still read against it.
 */
object Memories : UuidTable("memories") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /**
     * The core memory this one is a detail of, null for a core memory itself.
     *
     * Self-referencing rather than two tables, because the two are the same kind of thing at two
     * levels of detail -- both are a line somebody could read, both have a time they were true, both
     * were learned from a mail. What differs is only when they are shown.
     */
    val parent = reference("parent_id", Memories, onDelete = ReferenceOption.CASCADE).nullable()

    /**
     * What this is about, in one or two words: "Studium", "Arbeit", "Umzug", "Chor".
     *
     * Filled on a core memory and null on a detail. It is what the model reads first and what it
     * asks for more about, so it is the label rather than the sentence.
     */
    val topic = varchar("topic", 128).nullable()

    /**
     * The line itself, as somebody would say it: "Informatik an der TU Dresden, seit Oktober 2024."
     *
     * Short for a core memory, because all of them are shown together. As long as it needs to be for
     * a detail, because a detail is only ever read on purpose.
     */
    val content = text("content")

    /**
     * When this started being true, null where nobody knows.
     *
     * Null rather than a guessed day. A memory with an invented beginning is worse than one with
     * none: the one with none is shown for every mail, the one with a wrong beginning goes missing
     * for exactly the mail it would have explained.
     */
    val relevantFrom = timestamp("relevant_from").nullable()

    /**
     * When it stopped, null while it has not.
     *
     * The reason a memory is closed rather than deleted, and the reason the mail's own date decides
     * what is shown: a mail from 2022 is read against what was true in 2022. A finished degree
     * explains three years of mail and nothing after it, and keeping it out of the context of
     * everything since is what keeps that context worth reading.
     */
    val relevantTo = timestamp("relevant_to").nullable()

    /**
     * The mail this was learned from, null for what a reader wrote themselves -- and null again once
     * that mail is gone, which is what the memory outliving its source means.
     */
    val learnedFrom = reference("learned_from_id", Emails, onDelete = ReferenceOption.SET_NULL).nullable()

    val createdAt = timestamp("created_at")

    /** False for a memory the reader wrote themselves, which the agent may not change. */
    val createdByAgent = bool("created_by_agent")

    init {
        // The one lookup that happens per mail: this reader's core memories. The details hang off
        // their parent and are only ever fetched by it.
        index(isUnique = false, user, parent)
    }
}

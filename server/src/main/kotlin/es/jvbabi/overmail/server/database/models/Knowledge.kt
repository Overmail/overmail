package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.Uuid

/**
 * Something the assistant learned about a user and their mail, and kept.
 *
 * Written by the agent -- in a chat, or while a mail is being classified -- and read back by
 * keyword rather than in full: a mailbox collects hundreds of these over time and a prompt has
 * room for a handful. That is what [Knowledges.keywords] is for; the agent writes the words it
 * would look for itself.
 */
class Knowledge(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<Knowledge>(Knowledges) {
        /** The handle of an entry, trimmed and with inner whitespace as one space. */
        fun normalizeName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

        /**
         * A keyword as it is stored and looked up: trimmed, lowercase, and inner whitespace as
         * one space. Empty when nothing is left of it.
         */
        fun normalizeKeyword(keyword: String): String =
            keyword.trim().lowercase().replace(Regex("\\s+"), " ")

        /** The list as one column value; see [Knowledges.keywords] for why it is a list at all. */
        fun joinKeywords(keywords: List<String>): String = keywords
            .map { normalizeKeyword(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORDS)
            .joinToString(",")

        fun splitKeywords(value: String): List<String> = value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        /**
         * Enough for a subject, a sender, an order number and a few words about it. More than
         * that is a sign the agent is writing the entry into its own keywords.
         */
        const val MAX_KEYWORDS = 24
    }

    typealias Id = Uuid

    var owner by User referencedOn Knowledges.owner
    var name by Knowledges.name
    var description by Knowledges.description
    var keywords by Knowledges.keywords
    var relevantOn by Knowledges.relevantOn
    var createdAt by Knowledges.createdAt
    var updatedAt by Knowledges.updatedAt
    var createdByAgent by Knowledges.createdByAgent
}

object Knowledges : UuidTable("knowledges") {
    /** Knowledge is about one user's mail, and never leaves them. */
    val owner = reference("owner_id", Users, onDelete = ReferenceOption.CASCADE)

    /** What the entry is about, in a few words. Also its handle: writing the same name updates it. */
    val name = varchar("name", 255)

    val description = text("description")

    /**
     * The words to find this entry by, comma separated and normalized (see
     * [Knowledge.joinKeywords]).
     *
     * A column rather than a table of its own: they are part of the entry the agent writes and
     * are only ever read with it, and a mailbox's worth of knowledge is matched in memory anyway
     * -- the search is fuzzy, which no index would answer.
     */
    val keywords = text("keywords")

    /**
     * The day this is about, for the entries where that is the point -- a deadline, a move, the
     * start of a semester. Null for knowledge that is not tied to a date, which is most of it.
     */
    val relevantOn = date("relevant_on").nullable()

    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** Bumped on every write: the search puts what was learned last first among equals. */
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)

    val createdByAgent = bool("created_by_agent")

    init {
        // One entry per name and user: writing a name that is already there updates that entry
        // rather than leaving two versions of the same learning side by side.
        uniqueIndex(owner, name)
    }
}

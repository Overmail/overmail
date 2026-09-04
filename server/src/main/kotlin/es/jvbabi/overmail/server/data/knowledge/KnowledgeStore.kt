package es.jvbabi.overmail.server.data.knowledge

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.models.Knowledge
import es.jvbabi.overmail.server.database.models.Knowledges
import es.jvbabi.overmail.server.database.models.User
import es.jvbabi.overmail.server.util.fuzzyContains
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

/** How much of an entry a search hands back, so a handful of hits stay a handful of lines. */
private const val EXCERPT_CHARS = 240

/**
 * The one way in and out of what the assistant knows about a user.
 *
 * Read by keyword, never in full: knowledge is written over months and a prompt has room for a
 * few entries, so the caller says what it is looking for and gets the entries whose keywords
 * account for it. Everything here is scoped to one user -- their knowledge is about their mail.
 *
 * Used from two places, which is why it is a store rather than a couple of queries in a tool:
 * the chat agent reaches it through its tools, and the classification reads it into its prompt
 * and writes back what it learned while sorting a mail.
 */
class KnowledgeStore(private val database: OvermailDatabase) {

    /** One entry, with [excerpt] set where the caller only asked for a search. */
    data class Entry(
        val id: Knowledge.Id,
        val name: String,
        val description: String,
        val keywords: List<String>,
        val relevantOn: LocalDate?,
        val updatedAt: Instant,
    ) {
        val excerpt: String
            get() = if (description.length <= EXCERPT_CHARS) description
            else description.take(EXCERPT_CHARS).trimEnd() + "..."
    }

    /** The entry as it stands after a write, and whether there was one of that name before. */
    data class Written(val entry: Entry, val existed: Boolean)

    /**
     * The entries whose keywords or name account for [query], most of it first and the freshest
     * of those first.
     *
     * The query is words, not a sentence: each of them is looked for in the keywords and in the
     * name, and an entry counts for as many of them as it carries. Fuzzy, like the rest of the
     * search in this app, so "rechnung" finds "rechnungen" -- and matched in memory, because a
     * user's knowledge is a few hundred short rows and no index answers a subsequence match.
     *
     * An empty query is not an empty answer: it is the freshest entries, which is what to hand
     * somebody who asks what is known at all.
     */
    suspend fun search(userId: User.Id, query: String, limit: Int): List<Entry> {
        val terms = terms(query)

        return database.query {
            val entries = Knowledges
                .select(
                    Knowledges.id,
                    Knowledges.name,
                    Knowledges.description,
                    Knowledges.keywords,
                    Knowledges.relevantOn,
                    Knowledges.updatedAt,
                )
                .where { Knowledges.owner eq userId }
                .orderBy(Knowledges.updatedAt, SortOrder.DESC)
                .map { row -> row.toEntry() }

            if (terms.isEmpty()) return@query entries.take(limit)

            entries
                .map { entry -> entry to entry.score(terms) }
                .filter { (_, score) -> score > 0 }
                // The order within one score is the order the rows came in, which is by
                // updatedAt: what was learned last wins a tie.
                .sortedByDescending { (_, score) -> score }
                .take(limit)
                .map { (entry, _) -> entry }
        }
    }

    /** One entry in full, or null when it is not this user's. */
    suspend fun read(userId: User.Id, id: Knowledge.Id): Entry? = database.query {
        Knowledges
            .select(
                Knowledges.id,
                Knowledges.name,
                Knowledges.description,
                Knowledges.keywords,
                Knowledges.relevantOn,
                Knowledges.updatedAt,
            )
            // The ownership is part of the lookup: there is no moment where a foreign entry has
            // been read.
            .where { (Knowledges.id eq id) and (Knowledges.owner eq userId) }
            .singleOrNull()
            ?.toEntry()
    }

    /**
     * Writes an entry, or updates the one of that name.
     *
     * The name is the handle, case-insensitively: learning more about "Stromvertrag" is that
     * entry saying more, not a second entry beside it. What is written is what the caller sends
     * -- the whole description and the whole keyword list -- so an update means reading the entry
     * first and sending it back with the addition. That is the contract a model can follow; a
     * merge here would guess at which half is newer.
     */
    suspend fun write(
        userId: User.Id,
        name: String,
        description: String,
        keywords: List<String>,
        relevantOn: LocalDate?,
        byAgent: Boolean,
    ): Written {
        val cleanName = Knowledge.normalizeName(name)
        val joinedKeywords = Knowledge.joinKeywords(keywords)
        val now = Clock.System.now()

        return database.query {
            val existing = Knowledges
                .select(Knowledges.id)
                .where { (Knowledges.owner eq userId) and (Knowledges.name.lowerCase() eq cleanName.lowercase()) }
                .limit(1)
                .singleOrNull()
                ?.get(Knowledges.id)
                ?.value

            val id = if (existing == null) {
                Knowledges.insertAndGetId {
                    it[Knowledges.owner] = userId
                    it[Knowledges.name] = cleanName
                    it[Knowledges.description] = description.trim()
                    it[Knowledges.keywords] = joinedKeywords
                    it[Knowledges.relevantOn] = relevantOn
                    it[Knowledges.createdByAgent] = byAgent
                    it[Knowledges.updatedAt] = now
                }.value
            } else {
                Knowledges.update({ Knowledges.id eq existing }) {
                    // The name keeps the spelling it was stored under; the rest is replaced.
                    it[Knowledges.description] = description.trim()
                    it[Knowledges.keywords] = joinedKeywords
                    it[Knowledges.relevantOn] = relevantOn
                    it[Knowledges.updatedAt] = now
                }
                existing
            }

            val entry = Knowledges
                .select(
                    Knowledges.id,
                    Knowledges.name,
                    Knowledges.description,
                    Knowledges.keywords,
                    Knowledges.relevantOn,
                    Knowledges.updatedAt,
                )
                .where { Knowledges.id eq id }
                .single()
                .toEntry()

            Written(entry = entry, existed = existing != null)
        }
    }
}

/** What an entry is worth for a query: how many of its words the entry carries. */
private fun KnowledgeStore.Entry.score(terms: List<String>): Int {
    val haystacks = keywords + name
    return terms.count { term -> haystacks.any { hay -> hay.fuzzyContains(term) } }
}

/**
 * A query as the words to look for: commas and whitespace separate them, and a single letter is
 * not a keyword -- a subsequence match would answer it with everything.
 */
private fun terms(query: String): List<String> = query
    .split(",", " ", "\n", "\t")
    .map { it.trim().lowercase() }
    .filter { it.length > 1 }
    .distinct()

private fun org.jetbrains.exposed.v1.core.ResultRow.toEntry() = KnowledgeStore.Entry(
    id = this[Knowledges.id].value,
    name = this[Knowledges.name],
    description = this[Knowledges.description],
    keywords = Knowledge.splitKeywords(this[Knowledges.keywords]),
    relevantOn = this[Knowledges.relevantOn],
    updatedAt = this[Knowledges.updatedAt],
)

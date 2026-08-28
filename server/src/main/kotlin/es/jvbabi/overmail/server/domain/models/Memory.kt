package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * One thing the mailbox knows about its reader, see
 * [es.jvbabi.overmail.server.database.models.Memories].
 *
 * A core memory ([isCore]) is a line short enough to be shown for every mail; a detail is one that
 * is only read when something asks about its topic. Both carry the stretch of time they are true
 * for, and both are closed rather than deleted when they stop.
 */
data class Memory(
    val id: Uuid,
    val userId: Uuid,
    /** The core memory this is a detail of, null for a core memory. */
    val parentId: Uuid?,
    /** What it is about, in a word or two. Filled on a core memory, null on a detail. */
    val topic: String?,
    val content: String,
    val relevantFrom: Instant?,
    val relevantTo: Instant?,
    /** The mail it was learned from, null for what a reader wrote and for a mail since deleted. */
    val learnedFromEmailId: Uuid?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
) {
    /** Whether this is one of the lines shown for every mail, rather than a detail behind one. */
    val isCore: Boolean get() = parentId == null

    /**
     * Whether this was true when [at] happened.
     *
     * Both ends open on purpose. A memory with no beginning is shown for every mail, which is right
     * for the things nobody can date -- "arbeitet als Werkstudent" is true of the mail before the
     * one that mentioned it too. A memory with no end is still going on, which is not the same as
     * forever: it is what makes closing it later an honest change rather than a correction.
     */
    fun isRelevantAt(at: Instant): Boolean =
        (relevantFrom == null || relevantFrom <= at) && (relevantTo == null || at <= relevantTo)

    /** The stretch it covers, as a person would write it. Empty where neither end is known. */
    fun periodAsText(): String = when {
        relevantFrom != null && relevantTo != null -> "${relevantFrom.asDay()} bis ${relevantTo.asDay()}"
        relevantFrom != null -> "seit ${relevantFrom.asDay()}"
        relevantTo != null -> "bis ${relevantTo.asDay()}"
        else -> ""
    }
}

/** The day, which is all the precision a memory ever has. */
private fun Instant.asDay(): String = toString().take(10)

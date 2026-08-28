package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Memory
import es.jvbabi.overmail.server.domain.models.User
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface MemoryRepository {
    /**
     * The reader's core memories that were true at [at], oldest first.
     *
     * [at] is the mail's own date and not now, which is the whole reason a memory carries a stretch
     * of time: a mail from 2022 is read against what was true in 2022, and a degree finished since
     * then explains that mail without cluttering the context of everything after it. Null asks for
     * all of them regardless, which is what a screen showing the reader their own memories wants.
     *
     * Core memories only. The details behind them are fetched per topic, see [detailsOf], because
     * putting all of them in front of a model is exactly what this shape exists to avoid.
     */
    suspend fun coreMemories(user: User, at: Instant? = null): List<Memory>

    /**
     * What is known about one core memory beyond its own line, oldest first.
     *
     * Filtered by [at] the same way when it is given: a detail is as datable as the thing it belongs
     * to, and one about a job that ended has no business explaining this week's mail.
     */
    suspend fun detailsOf(memoryId: Uuid, at: Instant? = null): List<Memory>

    /**
     * Writes one down. [parentId] null makes it a core memory, otherwise a detail of that one.
     *
     * Nothing is deduplicated here. Whether the mailbox already knows this is a question about
     * meaning, and the only thing that can answer it has read the mail -- see the revision step,
     * which is shown what is already known before it is allowed to add to it.
     */
    suspend fun remember(
        user: User,
        topic: String?,
        content: String,
        parentId: Uuid? = null,
        relevantFrom: Instant? = null,
        relevantTo: Instant? = null,
        learnedFromEmailId: Uuid? = null,
        createdByAgent: Boolean,
    ): Memory

    /**
     * Ends a memory as of [on], and answers with it as it now reads. Null where there is no such
     * memory, or where it is not the agent's to close and [onlyIfByAgent] was asked for.
     *
     * Closed and not deleted: a memory that stopped being true is still what the mail of its own
     * years is read against. The guard is here rather than in the caller because it is the kind of
     * rule that has to hold even when somebody forgets to check -- what a reader wrote about their
     * own life is not the agent's to end.
     */
    suspend fun close(memoryId: Uuid, on: Instant, onlyIfByAgent: Boolean = false): Memory?

    /** One memory by id, or null. */
    suspend fun byId(memoryId: Uuid): Memory?
}

package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.toMemory
import es.jvbabi.overmail.server.database.models.Memories
import es.jvbabi.overmail.server.domain.models.Memory
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class MemoryRepositoryImpl(
    private val database: OvermailDatabase,
) : MemoryRepository {

    override suspend fun coreMemories(user: User, at: Instant?): List<Memory> {
        val mine = database.query {
            Memories
                .selectAll()
                .where((Memories.user eq user.id) and (Memories.parent eq null))
                // Oldest first, so the handles a run hands out come in the same order every time.
                .orderBy(Memories.createdAt to SortOrder.ASC, Memories.id to SortOrder.ASC)
                .map { it.toMemory() }
                .toList()
        }

        return mine.relevantAt(at)
    }

    override suspend fun detailsOf(memoryId: Uuid, at: Instant?): List<Memory> {
        val details = database.query {
            Memories
                .selectAll()
                .where(Memories.parent eq memoryId)
                .orderBy(Memories.createdAt to SortOrder.ASC, Memories.id to SortOrder.ASC)
                .map { it.toMemory() }
                .toList()
        }

        return details.relevantAt(at)
    }

    override suspend fun remember(
        user: User,
        topic: String?,
        content: String,
        parentId: Uuid?,
        relevantFrom: Instant?,
        relevantTo: Instant?,
        learnedFromEmailId: Uuid?,
        createdByAgent: Boolean,
    ): Memory {
        val line = content.trim()
        val label = topic?.trim()?.take(TOPIC_LENGTH)?.takeIf { it.isNotEmpty() }
        val createdAt = Clock.System.now()

        val id = database.query {
            Memories.insertAndGetId {
                it[Memories.user] = user.id
                it[Memories.parent] = parentId
                it[Memories.topic] = label
                it[Memories.content] = line
                it[Memories.relevantFrom] = relevantFrom
                it[Memories.relevantTo] = relevantTo
                it[Memories.learnedFrom] = learnedFromEmailId
                it[Memories.createdAt] = createdAt
                it[Memories.createdByAgent] = createdByAgent
            }.value
        }

        return Memory(
            id = id,
            userId = user.id,
            parentId = parentId,
            topic = label,
            content = line,
            relevantFrom = relevantFrom,
            relevantTo = relevantTo,
            learnedFromEmailId = learnedFromEmailId,
            createdAt = createdAt,
            createdByAgent = createdByAgent,
        )
    }

    override suspend fun close(memoryId: Uuid, on: Instant, onlyIfByAgent: Boolean): Memory? {
        return database.query {
            // The guard is part of the update rather than a read before it: two statements would be
            // a window in which the memory changes hands.
            val closed = Memories.update({
                val row = Memories.id eq memoryId

                if (onlyIfByAgent) row and (Memories.createdByAgent eq true) else row
            }) {
                it[Memories.relevantTo] = on
            }

            if (closed == 0) return@query null

            Memories
                .selectAll()
                .where(Memories.id eq memoryId)
                .firstRowOrNull()
                ?.toMemory()
        }
    }

    override suspend fun byId(memoryId: Uuid): Memory? {
        return database.query {
            Memories
                .selectAll()
                .where(Memories.id eq memoryId)
                .firstRowOrNull()
                ?.toMemory()
        }
    }
}

/**
 * Everything that was true at that moment, or everything at all where no moment is given.
 *
 * Filtered here rather than in the query, and not for want of an SQL way to say it: the rule is
 * [Memory.isRelevantAt] and it is worth having in exactly one place -- two open ends, each meaning
 * something particular, is the kind of condition that drifts when it is written twice. A reader has
 * tens of memories, so the rows this reads and throws away cost nothing worth the duplication.
 */
private fun List<Memory>.relevantAt(at: Instant?): List<Memory> =
    if (at == null) this else filter { it.isRelevantAt(at) }

private const val TOPIC_LENGTH = 128

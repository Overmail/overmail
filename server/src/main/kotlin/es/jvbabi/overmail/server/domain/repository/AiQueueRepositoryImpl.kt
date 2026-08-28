package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toAiQueueEntry
import es.jvbabi.overmail.server.database.models.AiProcessingQueue
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.domain.models.AiQueueEntry
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class AiQueueRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : AiQueueRepository {

    override suspend fun enqueue(emailId: Uuid, reason: ClassificationReason): Boolean {
        return database.query {
            // Read and written in the one transaction, so two callers asking for the same mail at
            // the same moment cannot both decide it is not queued. The unique index on the mail is
            // what makes that a lost insert rather than two places in the queue either way.
            val queued = AiProcessingQueue
                .selectAll()
                .where(AiProcessingQueue.email eq emailId)
                .firstRowOrNull() != null

            if (queued) return@query false

            AiProcessingQueue.insertAndGetId {
                it[AiProcessingQueue.email] = emailId
                it[AiProcessingQueue.reason] = reason
                it[AiProcessingQueue.enqueuedAt] = Clock.System.now()
            }

            true
        }
    }

    override suspend fun next(): AiQueueEntry? {
        return database.query {
            AiProcessingQueue
                .selectAll()
                .where(AiProcessingQueue.attempts less MAX_QUEUE_ATTEMPTS)
                .orderBy(
                    AiProcessingQueue.enqueuedAt to SortOrder.ASC,
                    AiProcessingQueue.id to SortOrder.ASC,
                )
                .limit(1)
                .firstRowOrNull()
                ?.toAiQueueEntry()
        }
    }

    override suspend fun done(entryId: Uuid) {
        database.query {
            AiProcessingQueue.deleteWhere { id eq entryId }
        }
    }

    override suspend fun failed(entryId: Uuid, why: String) {
        database.query {
            val entry = AiProcessingQueue
                .selectAll()
                .where(AiProcessingQueue.id eq entryId)
                .firstRowOrNull()
                ?.toAiQueueEntry()
                ?: return@query

            AiProcessingQueue.update({ AiProcessingQueue.id eq entryId }) {
                it[AiProcessingQueue.attempts] = entry.attempts + 1
                it[AiProcessingQueue.lastError] = why.take(MAX_ERROR)
            }
        }
    }

    override suspend fun pendingFor(user: User): Int {
        return database.query {
            (AiProcessingQueue innerJoin Emails innerJoin ImapAccounts)
                .selectAll()
                .where(
                    (ImapAccounts.user eq user.id) and
                        (AiProcessingQueue.attempts less MAX_QUEUE_ATTEMPTS)
                )
                .count()
                .toInt()
        }
    }

    override fun changes(): Flow<Unit> = changes.changesOf(AiProcessingQueue).map { }
}

/** How much of a failure is worth keeping. Enough to tell what went wrong, not a whole stack. */
private const val MAX_ERROR = 2_000

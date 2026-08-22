package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toArchiveEntry
import es.jvbabi.overmail.server.database.models.Archived
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.domain.models.ArchiveEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class ArchiveRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : ArchiveRepository {

    override fun getEntriesForEmail(emailId: Uuid): Flow<List<ArchiveEntry>> {
        return changes.changesOf(Archived)
            .conflate()
            .map {
                database.query {
                    Archived
                        .selectAll()
                        .where(Archived.email eq emailId)
                        // The id only breaks ties: two changes in the same instant are not what
                        // this table is for, but the order still has to be one order.
                        .orderBy(Archived.createdAt to SortOrder.ASC, Archived.id to SortOrder.ASC)
                        .map { it.toArchiveEntry() }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun isArchived(emailId: Uuid): Flow<Boolean> {
        return changes.changesOf(Emails)
            .conflate()
            .map {
                database.query {
                    Emails
                        .select(Emails.isArchived)
                        .where(Emails.id eq emailId)
                        .map { it[Emails.isArchived] }
                        .firstRowOrNull() ?: false
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun setArchived(
        emailId: Uuid,
        isArchived: Boolean,
        createdByAgent: Boolean,
    ): ArchiveEntry? {
        return database.query {
            // The flag and the entry are written in the same transaction as the state they are
            // read from, so the two cannot come apart: a mail is never archived without an entry
            // saying who archived it, and no entry describes a change that did not happen.
            val current = Emails
                .select(Emails.isArchived)
                .where(Emails.id eq emailId)
                .map { it[Emails.isArchived] }
                .firstRowOrNull()

            if (current == null || current == isArchived) return@query null

            val createdAt = Clock.System.now()
            val id = Archived.insertAndGetId {
                it[Archived.email] = emailId
                it[Archived.isArchived] = isArchived
                it[Archived.createdAt] = createdAt
                it[Archived.createdByAgent] = createdByAgent
            }.value

            Emails.update({ Emails.id eq emailId }) {
                it[Emails.isArchived] = isArchived
            }

            ArchiveEntry(
                id = id,
                isArchived = isArchived,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }
}

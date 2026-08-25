package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toSpamEntry
import es.jvbabi.overmail.server.database.mappers.toSpamFilter
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailSpam
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.Filters
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.SpamEntry
import es.jvbabi.overmail.server.domain.models.SpamFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class SpamRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : SpamRepository {

    /** The format stored rules are read back from, see [SpamFilterRepositoryImpl]. */
    private val json = Json

    override fun getEntriesForEmail(emailId: Uuid): Flow<List<SpamEntry>> {
        // The filters and their owners too: an entry carries the filter that caught the mail, so
        // renaming a filter changes what these entries read as.
        return changes.changesOf(EmailSpam, Filters, Users)
            .conflate()
            .map {
                database.query {
                    EmailSpam
                        .selectAll()
                        .where(EmailSpam.email eq emailId)
                        // The id only breaks ties: two changes in the same instant are not what
                        // this table is for, but the order still has to be one order.
                        .orderBy(EmailSpam.createdAt to SortOrder.ASC, EmailSpam.id to SortOrder.ASC)
                        .map { it.toSpamEntry(filterOf(it[EmailSpam.filter]?.value)) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun isSpam(emailId: Uuid): Flow<Boolean> {
        return changes.changesOf(Emails)
            .conflate()
            .map {
                database.query {
                    Emails
                        .select(Emails.isSpam)
                        .where(Emails.id eq emailId)
                        .map { it[Emails.isSpam] }
                        .firstRowOrNull() ?: false
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun setSpam(emailId: Uuid, isSpam: Boolean, filterId: Uuid?): SpamEntry? {
        return database.query {
            // The flag and the entry are written in the same transaction as the state they are
            // read from, so the two cannot come apart: a mail never counts as spam without an
            // entry saying what flagged it, and no entry describes a change that did not happen.
            val current = Emails
                .select(Emails.isSpam)
                .where(Emails.id eq emailId)
                .map { it[Emails.isSpam] }
                .firstRowOrNull()

            if (current == null || current == isSpam) return@query null

            val createdAt = Clock.System.now()
            val id = EmailSpam.insertAndGetId {
                it[EmailSpam.email] = emailId
                it[EmailSpam.isSpam] = isSpam
                it[EmailSpam.filter] = filterId
                it[EmailSpam.createdAt] = createdAt
            }.value

            Emails.update({ Emails.id eq emailId }) {
                it[Emails.isSpam] = isSpam
            }

            SpamEntry(
                id = id,
                isSpam = isSpam,
                filter = filterOf(filterId),
                createdAt = createdAt,
            )
        }
    }

    override suspend fun attributeToFilter(emailId: Uuid, filterId: Uuid): SpamEntry? {
        return database.query {
            val newest = EmailSpam
                .selectAll()
                .where(EmailSpam.email eq emailId)
                .orderBy(EmailSpam.createdAt to SortOrder.DESC, EmailSpam.id to SortOrder.DESC)
                .limit(1)
                .firstRowOrNull()
                ?: return@query null

            // Only what is flagged now: attributing the entry that took a mail back out of spam
            // would read as "this filter unflagged it", which is not a thing a filter does.
            if (!newest[EmailSpam.isSpam]) return@query null

            val entryId = newest[EmailSpam.id].value
            EmailSpam.update({ EmailSpam.id eq entryId }) {
                it[EmailSpam.filter] = filterId
            }

            newest.toSpamEntry(filterOf(filterId))
        }
    }

    /** The filter an entry names, or null for one that names none. */
    private suspend fun R2dbcTransaction.filterOf(filterId: Uuid?): SpamFilter? {
        if (filterId == null) return null

        return Filters
            .innerJoin(Users)
            .selectAll()
            .where(Filters.id eq filterId)
            .map { it.toSpamFilter(it.toUser(), json) }
            .firstRowOrNull()
    }
}

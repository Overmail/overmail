package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toSpamFilter
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.Filters
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.spam.SpamRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class SpamFilterRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : SpamFilterRepository {

    /**
     * The one format a stored rule is written in and read back from. Default settings: the tree
     * carries its own tag, so nothing about it depends on how this is configured.
     */
    private val json = Json

    override fun getForUser(user: User): Flow<List<SpamFilter>> {
        return changes.changesOf(Filters)
            .conflate()
            .map {
                database.query {
                    Filters
                        .selectAll()
                        .where(Filters.user eq user.id)
                        // The id only breaks ties, as in the archive: two filters written in the
                        // same instant still have to come out in one order.
                        .orderBy(Filters.createdAt to SortOrder.ASC, Filters.id to SortOrder.ASC)
                        // The caller handed the user in, so this is not read off the row.
                        .map { it.toSpamFilter(user, json) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun getById(id: Uuid): Flow<SpamFilter?> {
        return changes.changesOf(Filters, Users)
            .conflate()
            .map {
                database.query {
                    Filters
                        .innerJoin(Users)
                        .selectAll()
                        .where(Filters.id eq id)
                        .map { it.toSpamFilter(it.toUser(), json) }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun insert(
        user: User,
        name: String,
        rule: SpamRule,
        isActive: Boolean,
    ): SpamFilter {
        val createdAt = Clock.System.now()

        val id = database.query {
            Filters.insertAndGetId {
                it[Filters.user] = user.id
                it[Filters.name] = name
                it[Filters.rule] = json.encodeToString<SpamRule>(rule)
                it[Filters.isActive] = isActive
                it[Filters.createdAt] = createdAt
            }.value
        }

        return SpamFilter(
            id = id,
            user = user,
            name = name,
            rule = rule,
            isActive = isActive,
            createdAt = createdAt,
        )
    }

    override suspend fun update(
        id: Uuid,
        name: String,
        rule: SpamRule,
        isActive: Boolean,
    ): SpamFilter? {
        return database.query {
            val updated = Filters.update({ Filters.id eq id }) {
                it[Filters.name] = name
                it[Filters.rule] = json.encodeToString<SpamRule>(rule)
                it[Filters.isActive] = isActive
            }

            if (updated == 0) return@query null

            // Read back rather than assembled from the arguments: the row carries who owns the
            // filter and when it was written, and neither is this function's to make up.
            Filters
                .innerJoin(Users)
                .selectAll()
                .where(Filters.id eq id)
                .map { it.toSpamFilter(it.toUser(), json) }
                .firstOrNull()
        }
    }
}

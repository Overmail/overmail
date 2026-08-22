package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmailUser
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.uuid.Uuid

class EmailUserRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
): EmailUserRepository {

    override fun getForUser(user: User): Flow<List<EmailUser>> {
        return changes.changesOf(EmailUsers)
            .conflate()
            .map {
                database.query {
                    EmailUsers
                        .selectAll()
                        .where(EmailUsers.user eq user.id)
                        .map { it.toEmailUser(user) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun getById(id: Uuid): Flow<EmailUser?> {
        return changes.changesOf(EmailUsers, Users)
            .conflate()
            .map {
                database.query {
                    (EmailUsers innerJoin Users)
                        .selectAll()
                        .where(EmailUsers.id eq id)
                        .map { it.toEmailUser(it.toUser()) }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }

    override fun findByAddress(user: User, address: String): Flow<EmailUser?> {
        return changes.changesOf(EmailUsers)
            .conflate()
            .map {
                database.query {
                    EmailUsers
                        .selectAll()
                        .where((EmailUsers.user eq user.id) and (EmailUsers.address eq address))
                        .map { it.toEmailUser(user) }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun findOrCreate(user: User, address: String): EmailUser {
        return database.query {
            // No upsert: the row holds nothing but the key, so there would be nothing to update.
            // insertIgnore returns null once the address is known, and the lookup then finds it --
            // including the row a concurrent importer just committed.
            val id = EmailUsers.insertIgnoreAndGetId {
                it[EmailUsers.user] = user.id
                it[EmailUsers.address] = address
            }?.value

            if (id != null) return@query EmailUser(id = id, user = user, address = address)

            EmailUsers
                .selectAll()
                .where((EmailUsers.user eq user.id) and (EmailUsers.address eq address))
                .map { it.toEmailUser(user) }
                .first()
        }
    }

    override fun distinctAddresses(user: User): Flow<List<String>> = addresses(user, withoutAvatar = false)

    override fun distinctAddressesWithoutAvatar(user: User): Flow<List<String>> =
        addresses(user, withoutAvatar = true)

    override suspend fun linkAvatar(user: User, address: String, avatarId: Uuid): Int {
        return database.query {
            EmailUsers.update({ (EmailUsers.user eq user.id) and (EmailUsers.address eq address) }) {
                it[EmailUsers.avatar] = avatarId
            }
        }
    }

    /**
     * The address book as a list of addresses. Distinct is stated for the reader rather than for
     * the database -- the address is already unique per user -- and the order is stable so a
     * refresh walks the book the same way twice.
     */
    private fun addresses(user: User, withoutAvatar: Boolean): Flow<List<String>> {
        return changes.changesOf(EmailUsers)
            .conflate()
            .map {
                database.query {
                    var where = (EmailUsers.user eq user.id) as Op<Boolean>
                    if (withoutAvatar) where = where and EmailUsers.avatar.isNull()

                    EmailUsers
                        .select(EmailUsers.address)
                        .where(where)
                        .orderBy(EmailUsers.address)
                        .map { row -> row[EmailUsers.address] }
                        .toList()
                        .distinct()
                }
            }
            .distinctUntilChanged()
    }
}

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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsertReturning
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

    override suspend fun upsert(user: User, name: String?, address: String): EmailUser {
        return database.query {
            EmailUsers
                .upsertReturning(
                    EmailUsers.user, EmailUsers.address,
                    onUpdate = { it[EmailUsers.name] = coalesce(insertValue(EmailUsers.name), EmailUsers.name) },
                ) {
                    it[EmailUsers.user] = user.id
                    it[EmailUsers.address] = address
                    it[EmailUsers.name] = name
                }
                .map { it.toEmailUser(user) }
                .first()
        }
    }
}

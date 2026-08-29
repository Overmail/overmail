package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.data.ChangeNotifiers
import es.jvbabi.overmail.server.data.reloads
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.uuid.Uuid

class UserRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: ChangeNotifiers,
): UserRepository {
    override fun getById(id: Uuid): Flow<User?> {
        return changes.users.changesOfRow(id)
            .reloads()
            .conflate()
            .map {
                database.query {
                    Users
                        .selectAll()
                        .where(Users.id eq id)
                        .map { it.toUser() }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }

    override fun findByIdentifier(identifier: String): Flow<User?> {
        return changes.users.changes()
            .reloads()
            .conflate()
            .map {
                database.query {
                    Users
                        .selectAll()
                        .where((Users.username eq identifier) or (Users.email eq identifier))
                        .map { it.toUser() }
                        .firstOrNull()
                }
            }
            .distinctUntilChanged()
    }
}

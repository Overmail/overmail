package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.uuid.Uuid

class UserRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
): UserRepository {
    override fun getById(id: Uuid): Flow<User?> {
        return changes.changesOf(Users)
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
}

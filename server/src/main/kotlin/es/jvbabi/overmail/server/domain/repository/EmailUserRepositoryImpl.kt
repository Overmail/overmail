package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.data.ChangeNotifiers
import es.jvbabi.overmail.server.data.reloads
import es.jvbabi.overmail.server.database.OvermailDatabase
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.uuid.Uuid

class EmailUserRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: ChangeNotifiers,
): EmailUserRepository {

    override fun getForUser(user: User): Flow<List<EmailUser>> {
        return changes.emailUsers.changesOfOwner(user.id)
            .reloads()
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
        return changes.emailUsers.changesOfRow(id)
            .reloads()
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
        return changes.emailUsers.changesOfOwner(user.id)
            .reloads()
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
        val (emailUser, wasCreated) = database.query {
            // No upsert: the row holds nothing but the key, so there would be nothing to update.
            // insertIgnore returns null once the address is known, and the lookup then finds it --
            // including the row a concurrent importer just committed.
            val id = EmailUsers.insertIgnoreAndGetId {
                it[EmailUsers.user] = user.id
                it[EmailUsers.address] = address
            }?.value

            if (id != null) return@query EmailUser(id = id, user = user, address = address) to true

            EmailUsers
                .selectAll()
                .where((EmailUsers.user eq user.id) and (EmailUsers.address eq address))
                .map { it.toEmailUser(user) }
                .first() to false
        }

        // Only for a row this call actually wrote: the lookup returns rows whose insert was
        // already reported, by the importer of another account or by an earlier call.
        if (wasCreated) changes.emailUsers.created(user.id, emailUser.id)

        return emailUser
    }
}

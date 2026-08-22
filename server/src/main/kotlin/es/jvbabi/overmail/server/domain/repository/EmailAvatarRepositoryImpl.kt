package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmailAvatar
import es.jvbabi.overmail.server.database.models.EmailAvatars
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.domain.models.EmailAvatar
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class EmailAvatarRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : EmailAvatarRepository {

    override fun getForUser(user: User): Flow<List<EmailAvatar>> {
        // Both tables: a picture appears here through the link, so a link that comes or goes
        // changes this listing just as much as a new picture does.
        return changes.changesOf(EmailAvatars, EmailUsers)
            .conflate()
            .map {
                database.query {
                    (EmailUsers innerJoin EmailAvatars)
                        .select(
                            EmailUsers.address, EmailAvatars.id, EmailAvatars.avatarSource,
                            EmailAvatars.createdAt,
                        )
                        .where(EmailUsers.user eq user.id)
                        .map { row -> row.toEmailAvatar() }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    /** No `distinctUntilChanged`: `ByteArray` compares by identity, so it would never drop one. */
    override fun getImage(id: Uuid): Flow<ByteArray?> {
        return changes.changesOf(EmailAvatars)
            .conflate()
            .map {
                database.query {
                    EmailAvatars
                        .select(EmailAvatars.data)
                        .where(EmailAvatars.id eq id)
                        .map { it[EmailAvatars.data] }
                        .firstOrNull()
                }
            }
    }

    override suspend fun insert(image: ByteArray, source: String): Uuid {
        return database.query {
            EmailAvatars.insertAndGetId {
                it[EmailAvatars.data] = image
                it[EmailAvatars.avatarSource] = source
                it[EmailAvatars.createdAt] = Clock.System.now()
            }.value
        }
    }

    override suspend fun deleteForUser(user: User): Int {
        return database.query {
            // Read first, deleted by id afterwards: the ids are what the delete needs, and going
            // through a subquery on a nullable reference column buys nothing here.
            val linked = EmailUsers
                .select(EmailUsers.avatar)
                .where((EmailUsers.user eq user.id) and EmailUsers.avatar.isNotNull())
                .toList()
                .mapNotNull { it[EmailUsers.avatar]?.value }
                .distinct()

            if (linked.isEmpty()) return@query 0

            // Unlinked here rather than left to `ON DELETE SET NULL`, even though the column says
            // so: this and the delete are one transaction either way, and an address book pointing
            // at a picture that is gone would serve 404s to every row using it.
            EmailUsers.update({ EmailUsers.avatar inList linked }) { it[EmailUsers.avatar] = null }

            EmailAvatars.deleteWhere { EmailAvatars.id inList linked }
        }
    }
}

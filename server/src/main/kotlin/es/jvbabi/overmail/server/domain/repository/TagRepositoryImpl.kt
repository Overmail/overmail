package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmailTag
import es.jvbabi.overmail.server.database.mappers.toTag
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class TagRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : TagRepository {

    override fun getForUser(user: User): Flow<List<Tag>> {
        return changes.changesOf(Tags)
            .conflate()
            .map {
                database.query {
                    Tags
                        .selectAll()
                        .where(Tags.user eq user.id)
                        .map { it.toTag(user) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun getForEmail(email: Email): Flow<List<EmailTag>> {
        return changes.changesOf(EmailTags, Tags, Users)
            .conflate()
            .map {
                database.query {
                    (EmailTags innerJoin Tags innerJoin Users)
                        .selectAll()
                        .where(EmailTags.email eq email.id)
                        .map { it.toEmailTag(it.toTag(it.toUser())) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun findOrCreate(user: User, name: String, createdByAgent: Boolean): Tag {
        val trimmed = name.trim()

        return database.query {
            val existing = Tags
                .selectAll()
                .where((Tags.user eq user.id) and (Tags.name.lowerCase() eq trimmed.lowercase()))
                .firstRowOrNull()

            if (existing != null) return@query existing.toTag(user)

            val createdAt = Clock.System.now()
            val id = Tags.insertAndGetId {
                it[Tags.user] = user.id
                it[Tags.name] = trimmed
                it[Tags.createdAt] = createdAt
                it[Tags.createdByAgent] = createdByAgent
            }.value

            Tag(
                id = id,
                user = user,
                name = trimmed,
                description = null,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }

    override suspend fun attach(
        emailId: Uuid,
        tag: Tag,
        reason: String?,
        createdByAgent: Boolean,
    ): EmailTag? {
        return database.query {
            val isFiled = EmailTags
                .selectAll()
                .where((EmailTags.email eq emailId) and (EmailTags.tag eq tag.id))
                .firstRowOrNull() != null

            if (isFiled) return@query null

            val createdAt = Clock.System.now()
            val id = EmailTags.insertAndGetId {
                it[EmailTags.email] = emailId
                it[EmailTags.tag] = tag.id
                it[EmailTags.reason] = reason
                it[EmailTags.createdAt] = createdAt
                it[EmailTags.createdByAgent] = createdByAgent
            }.value

            EmailTag(
                id = id,
                tag = tag,
                reason = reason,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }

    override suspend fun detach(emailId: Uuid, tag: Tag): Boolean {
        return database.query {
            EmailTags.deleteWhere { (email eq emailId) and (EmailTags.tag eq tag.id) } > 0
        }
    }
}

package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmailTag
import es.jvbabi.overmail.server.database.mappers.toTag
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TagUsage
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
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

    override suspend fun usageForUser(user: User): List<TagUsage> {
        return database.query {
            val mails = EmailTags.email.count()

            // Left join and a count in the database: a tag nothing carries is part of the answer,
            // and counting them here rather than over loaded rows keeps it one query either way.
            (Tags leftJoin EmailTags)
                .select(Tags.columns + mails)
                .where(Tags.user eq user.id)
                .groupBy(Tags.id)
                .orderBy(mails to SortOrder.DESC, Tags.name to SortOrder.ASC)
                .map { TagUsage(tag = it.toTag(user), mails = it[mails].toInt()) }
                .toList()
        }
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

    override suspend fun detach(emailId: Uuid, tag: Tag, onlyIfAgentAttached: Boolean): Boolean {
        return database.query {
            EmailTags.deleteWhere {
                val filing = (email eq emailId) and (EmailTags.tag eq tag.id)

                // The condition is part of the delete rather than a read before it: a check and a
                // delete in two statements is a window in which the user attaches the tag they are
                // about to lose it under.
                if (onlyIfAgentAttached) filing and (createdByAgent eq true) else filing
            } > 0
        }
    }

    override suspend fun mailsUnderTags(
        user: User,
        names: Collection<String>,
        before: Instant,
        limit: Int,
    ): List<Uuid> {
        if (names.isEmpty()) return emptyList()

        val wanted = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()

        return database.query {
            (EmailTags innerJoin Tags innerJoin Emails innerJoin ImapAccounts)
                .select(EmailTags.email, Emails.sent)
                .where(
                    (Tags.user eq user.id) and
                        (ImapAccounts.user eq user.id) and
                        (Tags.name.lowerCase() inList wanted) and
                        (Emails.sent less before)
                )
                .orderBy(Emails.sent to SortOrder.DESC, EmailTags.email to SortOrder.DESC)
                // A mail under two of the names comes back twice, so more rows are read than mails
                // are wanted and the list is cut to size after the duplicates are gone.
                .limit(limit * names.size)
                .toList()
                .map { it[EmailTags.email].value }
                .distinct()
                .take(limit)
        }
    }
}

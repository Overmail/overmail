package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmailTag
import es.jvbabi.overmail.server.database.mappers.toTag
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TaggedMail
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

/** How much of a neighbouring mail's text the caller gets to see. */
private const val EXCERPT_LENGTH = 400

/** One row of the neighbour query, before its tags are attached. */
private data class NeighbourRow(
    val id: Uuid,
    val subject: String,
    val sent: Instant,
    val sender: String,
    val excerpt: String?,
)

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

    override suspend fun detachAgentTag(emailId: Uuid, tagId: Uuid): Boolean {
        return database.query {
            EmailTags.deleteWhere {
                (EmailTags.email eq emailId) and
                    (EmailTags.tag eq tagId) and
                    // The agent may take back its own filing and nothing else.
                    (EmailTags.createdByAgent eq true)
            } > 0
        }
    }

    override suspend fun clearAgentWork(): ClearedAgentWork {
        return database.query {
            val links = EmailTags.deleteWhere { EmailTags.createdByAgent eq true }

            // Only the ones nothing points at any more: a tag the agent invented and a user has
            // since filed a mail under is theirs now.
            val tags = Tags.deleteWhere {
                (Tags.createdByAgent eq true) and
                    (Tags.id notInSubQuery EmailTags.select(EmailTags.tag))
            }

            ClearedAgentWork(links = links, created = tags)
        }
    }

    override fun findNeighbours(
        user: User,
        tagNames: Collection<String>,
        subject: String,
        before: Instant,
        limit: Int,
    ): Flow<List<TaggedMail>> {
        val wanted = tagNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val conversation = subject.withoutReplyPrefixes()
        if (wanted.isEmpty() && conversation.isEmpty()) return flowOf(emptyList())

        return changes.changesOf(EmailTags, Tags, Emails)
            .conflate()
            .map {
                database.query {
                    val filedUnderWanted = (EmailTags innerJoin Tags)
                        .select(EmailTags.email)
                        .where((Tags.user eq user.id) and (Tags.name.lowerCase() inList wanted))

                    // Either criterion is enough, so this cannot be a join: a mail answering another
                    // one shares no tag with it until it has been tagged itself.
                    var matches = Op.FALSE as Op<Boolean>
                    if (wanted.isNotEmpty()) matches = matches or (Emails.id inSubQuery filedUnderWanted)
                    if (conversation.isNotEmpty()) {
                        matches = matches or (Emails.subject.lowerCase() like "%${conversation.escapedForLike()}%")
                    }

                    // The excerpt is cut in the query: a newsletter's text part runs to tens of
                    // thousands of characters, and ten of those per mail processed would be read
                    // off the wire for nothing.
                    val excerpt = Emails.textContent.substring(1, EXCERPT_LENGTH)

                    val mails = (Emails innerJoin ImapAccounts innerJoin EmailUsers)
                        .select(Emails.id, Emails.subject, Emails.sent, EmailUsers.address, excerpt)
                        .where((ImapAccounts.user eq user.id) and (Emails.sent less before) and matches)
                        .withDistinct()
                        .orderBy(Emails.sent, SortOrder.DESC)
                        .limit(limit)
                        .map {
                            NeighbourRow(
                                id = it[Emails.id].value,
                                subject = it[Emails.subject],
                                sent = it[Emails.sent],
                                sender = it[EmailUsers.address],
                                excerpt = it[excerpt]?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null },
                            )
                        }
                        .toList()

                    if (mails.isEmpty()) return@query emptyList()

                    // Every tag of those mails, not just the ones searched for: the point is to see
                    // how they were filed in full.
                    val tagsByMail = (EmailTags innerJoin Tags)
                        .selectAll()
                        .where(EmailTags.email inList mails.map { it.id })
                        .toList()
                        .groupBy({ it[EmailTags.email].value }) { it.toEmailTag(it.toTag(user)) }

                    mails.map { mail ->
                        TaggedMail(
                            id = mail.id,
                            subject = mail.subject,
                            sent = mail.sent,
                            sender = mail.sender,
                            excerpt = mail.excerpt,
                            tags = tagsByMail[mail.id].orEmpty(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }
}

/** `Re:`, `AW:`, `Fwd:` and their friends, however often they have been stacked up in front. */
private val REPLY_PREFIX = Regex("""^\s*(re|aw|antw|fwd|fw|wg)\s*(\[\d+])?\s*:\s*""", RegexOption.IGNORE_CASE)

private fun String.withoutReplyPrefixes(): String {
    var subject = trim()
    while (true) {
        val stripped = subject.replaceFirst(REPLY_PREFIX, "")
        if (stripped == subject) return subject.lowercase()
        subject = stripped
    }
}

/** The subject is user input: its own wildcards must not widen the search. */
private fun String.escapedForLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

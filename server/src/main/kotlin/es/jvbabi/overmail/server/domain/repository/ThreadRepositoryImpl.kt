package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toMailThread
import es.jvbabi.overmail.server.database.mappers.toMailThreadEntry
import es.jvbabi.overmail.server.database.models.EmailThreads
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.Threads
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadEntry
import es.jvbabi.overmail.server.domain.models.ThreadOverview
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class ThreadRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
) : ThreadRepository {

    override fun getForUser(user: User): Flow<List<MailThread>> {
        return changes.changesOf(Threads)
            .conflate()
            .map {
                database.query {
                    Threads
                        .selectAll()
                        .where(Threads.user eq user.id)
                        .map { it.toMailThread(user) }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    override fun getOverviewForUser(user: User): Flow<List<ThreadOverview>> {
        return changes.changesOf(Threads, EmailThreads, Emails)
            .conflate()
            .map {
                database.query {
                    val threads = Threads
                        .selectAll()
                        .where(Threads.user eq user.id)
                        .map { it.toMailThread(user) }
                        .toList()
                        .associateBy { it.id }

                    if (threads.isEmpty()) return@query emptyList()

                    // One pass over every membership, ordered the way the mails will be shown, so
                    // the lists below come out sorted without sorting them: a thread's mails
                    // newest first, and the send time of the first one it collects is the thread's
                    // own rank.
                    val rows = (EmailThreads innerJoin Emails)
                        .select(EmailThreads.thread, EmailThreads.email, Emails.sent)
                        .where(EmailThreads.thread inList threads.keys.toList())
                        .orderBy(Emails.sent to SortOrder.DESC, Emails.id to SortOrder.DESC)
                        .toList()

                    val mailsOf = LinkedHashMap<Uuid, MutableList<Uuid>>()
                    val lastSentOf = HashMap<Uuid, Instant>()

                    for (row in rows) {
                        val threadId = row[EmailThreads.thread].value
                        mailsOf.getOrPut(threadId) { mutableListOf() } += row[EmailThreads.email].value
                        lastSentOf.putIfAbsent(threadId, row[Emails.sent])
                    }

                    // `mailsOf` keeps the order the memberships came in, so the thread whose newest
                    // mail is newest is first. A thread nothing is filed under any more is not in
                    // here at all, which is what leaves it out of the list.
                    mailsOf.map { (threadId, mailIds) ->
                        ThreadOverview(
                            thread = threads.getValue(threadId),
                            mailIds = mailIds,
                            lastSentAt = lastSentOf.getValue(threadId),
                        )
                    }
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun threadsOf(user: User, mailIds: Collection<Uuid>): Map<Uuid, MailThread> {
        if (mailIds.isEmpty()) return emptyMap()

        return database.query {
            (EmailThreads innerJoin Threads)
                .selectAll()
                .where((EmailThreads.email inList mailIds) and (Threads.user eq user.id))
                .toList()
                .associate { it[EmailThreads.email].value to it.toMailThread(user) }
        }
    }

    override suspend fun create(user: User, title: String, createdByAgent: Boolean): MailThread {
        val createdAt = Clock.System.now()
        val name = title.trim().take(TITLE_LENGTH)

        return database.query {
            val id = Threads.insertAndGetId {
                it[Threads.user] = user.id
                it[Threads.title] = name
                it[Threads.createdAt] = createdAt
                it[Threads.createdByAgent] = createdByAgent
            }.value

            MailThread(
                id = id,
                user = user,
                title = name,
                identifier = null,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }

    override suspend fun findOrCreateByIdentifier(
        user: User,
        identifier: String,
        title: String,
        createdByAgent: Boolean,
    ): MailThread {
        val matter = identifier.trim().take(IDENTIFIER_LENGTH)
        val name = title.trim().take(TITLE_LENGTH)

        return database.query {
            // Read and written in the one transaction, so two mails carrying the same number being
            // read at the same moment cannot both decide the thread is missing. The unique index on
            // (user, identifier) is what makes that a lost insert rather than two half threads
            // either way; this is what keeps it from being an exception on the ordinary second mail.
            val existing = Threads
                .selectAll()
                .where(
                    (Threads.user eq user.id) and
                        (Threads.identifier.lowerCase() eq matter.lowercase())
                )
                .firstRowOrNull()

            if (existing != null) return@query existing.toMailThread(user)

            val createdAt = Clock.System.now()
            val id = Threads.insertAndGetId {
                it[Threads.user] = user.id
                it[Threads.title] = name
                it[Threads.identifier] = matter
                it[Threads.createdAt] = createdAt
                it[Threads.createdByAgent] = createdByAgent
            }.value

            MailThread(
                id = id,
                user = user,
                title = name,
                identifier = matter,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }

    override suspend fun findByIdentifier(user: User, identifier: String): MailThread? {
        val matter = identifier.trim()
        if (matter.isEmpty()) return null

        return database.query {
            Threads
                .selectAll()
                .where(
                    (Threads.user eq user.id) and
                        (Threads.identifier.lowerCase() eq matter.lowercase())
                )
                .firstRowOrNull()
                ?.toMailThread(user)
        }
    }

    override suspend fun rename(thread: MailThread, title: String): MailThread? {
        val name = title.trim().take(TITLE_LENGTH)
        if (name.isEmpty()) return null

        return database.query {
            val renamed = Threads.update({ Threads.id eq thread.id }) {
                it[Threads.title] = name
            }

            if (renamed == 0) null else thread.copy(title = name)
        }
    }

    override suspend fun attach(
        emailId: Uuid,
        thread: MailThread,
        reason: String?,
        createdByAgent: Boolean,
    ): MailThreadEntry? {
        return database.query {
            val isMember = EmailThreads
                .selectAll()
                .where((EmailThreads.email eq emailId) and (EmailThreads.thread eq thread.id))
                .firstRowOrNull() != null

            if (isMember) return@query null

            val createdAt = Clock.System.now()
            val id = EmailThreads.insertAndGetId {
                it[EmailThreads.email] = emailId
                it[EmailThreads.thread] = thread.id
                it[EmailThreads.reason] = reason
                it[EmailThreads.createdAt] = createdAt
                it[EmailThreads.createdByAgent] = createdByAgent
            }.value

            MailThreadEntry(
                id = id,
                thread = thread,
                reason = reason,
                createdAt = createdAt,
                createdByAgent = createdByAgent,
            )
        }
    }
}

/** What the column takes, so a title cut here is cut the same way it is stored. */
private const val TITLE_LENGTH = 255

/** The same for an identifier: longer than this is not one, see `Threads.identifier`. */
private const val IDENTIFIER_LENGTH = 128

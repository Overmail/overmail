package es.jvbabi.overmail.server.domain.agent

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.MailIdentifier
import es.jvbabi.overmail.server.domain.models.MailPage
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.MailThread
import es.jvbabi.overmail.server.domain.models.MailThreadEntry
import es.jvbabi.overmail.server.domain.models.Memory
import es.jvbabi.overmail.server.domain.models.NewEmailRecipient
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TagUsage
import es.jvbabi.overmail.server.domain.models.ThreadOverview
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.repository.EmailRepository
import es.jvbabi.overmail.server.domain.repository.MailIdentifierRepository
import es.jvbabi.overmail.server.domain.repository.MemoryRepository
import es.jvbabi.overmail.server.domain.repository.TagRepository
import es.jvbabi.overmail.server.domain.repository.ThreadRepository
import es.jvbabi.overmail.server.domain.spam.MailFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The mailbox the agent's own classes are tested against: repositories that record what was asked
 * of them and nothing else.
 *
 * Shared by the two of them because they are the same mailbox seen from two sides -- one decides
 * what to change, the other decides when a matter becomes a thread -- and a second set of fakes
 * would be a second set of assumptions about the same rows.
 *
 * Every method the class under test does not call is `error("not used")` on purpose: a fake that
 * quietly answers a question nobody meant to ask is how a test comes to pass for the wrong reason.
 */

/** One tag attached to one mail, as a fake records it. */
internal data class Filing(val emailId: Uuid, val tag: Tag, val reason: String?)

/** Tags: what was attached and detached, rather than anything stored. */
internal class FakeTags : TagRepository {
    /** What [mailsUnderTags] answers, whatever it is asked. */
    var under: List<Uuid> = emptyList()

    /** What the mailbox already has, for the calls that look before they make a new one. */
    var existing: List<TagUsage> = emptyList()

    val attached = mutableListOf<Filing>()
    val detached = mutableListOf<Filing>()

    override suspend fun findOrCreate(user: User, name: String, createdByAgent: Boolean): Tag = Tag(
        id = Uuid.random(),
        user = user,
        name = name.trim(),
        description = null,
        createdAt = Clock.System.now(),
        createdByAgent = createdByAgent,
    )

    override suspend fun attach(
        emailId: Uuid,
        tag: Tag,
        reason: String?,
        createdByAgent: Boolean,
    ): EmailTag? {
        attached += Filing(emailId, tag, reason)

        return EmailTag(
            id = Uuid.random(),
            tag = tag,
            reason = reason,
            createdAt = Clock.System.now(),
            createdByAgent = createdByAgent,
        )
    }

    override suspend fun detach(emailId: Uuid, tag: Tag, onlyIfAgentAttached: Boolean): Boolean {
        detached += Filing(emailId, tag, null)

        return true
    }

    override suspend fun mailsUnderTags(
        user: User,
        names: Collection<String>,
        before: Instant,
        limit: Int,
    ): List<Uuid> = under

    override suspend fun usageForUser(user: User): List<TagUsage> = existing

    override fun getForUser(user: User): Flow<List<Tag>> = error("not used")
    override fun getForEmail(email: Email): Flow<List<EmailTag>> = error("not used")
}

/** Threads: what was opened, renamed and filled, and which of them an identifier finds. */
internal class FakeThreads : ThreadRepository {
    /** Which thread each mail sits in. */
    val of = mutableMapOf<Uuid, MailThread>()

    /** The threads that have an identifier, by that identifier in lower case. */
    val byIdentifier = mutableMapOf<String, MailThread>()

    val created = mutableListOf<MailThread>()
    val renamed = mutableListOf<Pair<Uuid, String>>()
    val attached = mutableListOf<Pair<Uuid, Uuid>>()

    override suspend fun threadsOf(user: User, mailIds: Collection<Uuid>): Map<Uuid, MailThread> =
        of.filterKeys { it in mailIds }

    override suspend fun create(user: User, title: String, createdByAgent: Boolean): MailThread =
        thread(user, title, identifier = null, createdByAgent = createdByAgent)

    override suspend fun findByIdentifier(user: User, identifier: String): MailThread? =
        byIdentifier[identifier.lowercase()]

    override suspend fun findOrCreateByIdentifier(
        user: User,
        identifier: String,
        title: String,
        createdByAgent: Boolean,
    ): MailThread = byIdentifier[identifier.lowercase()]
        ?: thread(user, title, identifier, createdByAgent).also {
            byIdentifier[identifier.lowercase()] = it
        }

    override suspend fun rename(thread: MailThread, title: String): MailThread? {
        renamed += thread.id to title

        return thread.copy(title = title)
    }

    override suspend fun attach(
        emailId: Uuid,
        thread: MailThread,
        reason: String?,
        createdByAgent: Boolean,
    ): MailThreadEntry? {
        attached += emailId to thread.id
        of[emailId] = thread

        return MailThreadEntry(
            id = Uuid.random(),
            thread = thread,
            reason = reason,
            createdAt = Clock.System.now(),
            createdByAgent = createdByAgent,
        )
    }

    override fun getForUser(user: User): Flow<List<MailThread>> = error("not used")
    override fun getOverviewForUser(user: User): Flow<List<ThreadOverview>> = error("not used")

    private fun thread(
        user: User,
        title: String,
        identifier: String?,
        createdByAgent: Boolean,
    ): MailThread {
        val thread = MailThread(
            id = Uuid.random(),
            user = user,
            title = title,
            identifier = identifier,
            createdAt = Clock.System.now(),
            createdByAgent = createdByAgent,
        )
        created += thread

        return thread
    }
}

/** The identifiers mails name, as a map from mail to string. */
internal class FakeMatters : MailIdentifierRepository {
    val of = mutableMapOf<Uuid, String>()

    override suspend fun record(emailId: Uuid, identifier: String): MailIdentifier? {
        if (of.containsKey(emailId)) return null
        of[emailId] = identifier

        return MailIdentifier(
            id = Uuid.random(),
            emailId = emailId,
            identifier = identifier,
            createdAt = Clock.System.now(),
        )
    }

    override suspend fun identifierOf(emailId: Uuid): String? = of[emailId]

    override suspend fun mailsWith(
        user: User,
        identifier: String,
        before: Instant?,
        limit: Int,
    ): List<Uuid> = of.filterValues { it.equals(identifier, ignoreCase = true) }.keys.toList()
}

/** What the mailbox knows about its reader, as a list that records what was written. */
internal class FakeMemories : MemoryRepository {
    val kept = mutableListOf<Memory>()

    override suspend fun coreMemories(user: User, at: Instant?): List<Memory> = kept
        .filter { it.isCore }
        .filter { at == null || it.isRelevantAt(at) }

    override suspend fun detailsOf(memoryId: Uuid, at: Instant?): List<Memory> = kept
        .filter { it.parentId == memoryId }
        .filter { at == null || it.isRelevantAt(at) }

    override suspend fun remember(
        user: User,
        topic: String?,
        content: String,
        parentId: Uuid?,
        relevantFrom: Instant?,
        relevantTo: Instant?,
        learnedFromEmailId: Uuid?,
        createdByAgent: Boolean,
    ): Memory {
        val memory = Memory(
            id = Uuid.random(),
            userId = user.id,
            parentId = parentId,
            topic = topic,
            content = content,
            relevantFrom = relevantFrom,
            relevantTo = relevantTo,
            learnedFromEmailId = learnedFromEmailId,
            createdAt = Clock.System.now(),
            createdByAgent = createdByAgent,
        )
        kept += memory

        return memory
    }

    override suspend fun close(memoryId: Uuid, on: Instant, onlyIfByAgent: Boolean): Memory? {
        val at = kept.indexOfFirst { it.id == memoryId }
        if (at < 0) return null
        if (onlyIfByAgent && !kept[at].createdByAgent) return null

        val closed = kept[at].copy(relevantTo = on)
        kept[at] = closed

        return closed
    }

    override suspend fun byId(memoryId: Uuid): Memory? = kept.firstOrNull { it.id == memoryId }
}

/** The mailbox as a listing. */
internal class FakeEmails(private val summaries: List<MailSummary>) : EmailRepository {

    override fun getSummariesForUser(
        user: User,
        limit: Int,
        after: Instant?,
        before: Instant?,
        newestFirst: Boolean,
        threadId: Uuid?,
        ids: Collection<Uuid>?,
        filed: Boolean?,
        archived: Boolean?,
        spam: Boolean?,
    ): Flow<MailPage> {
        val window = summaries
            .filter { ids == null || it.id in ids }
            .filter { before == null || it.sent < before }
            .filter { threadId == null || it.thread?.id == threadId }
            .sortedByDescending { it.sent }
            .take(limit)

        return flowOf(MailPage(mails = window, total = window.size))
    }

    override fun getById(id: Uuid): Flow<Email?> = flowOf(null)
    override fun getForImapAccount(imapAccount: ImapAccount): Flow<List<Email>> = error("not used")
    override fun getDailyCountsForUser(user: User, year: Int): Flow<Map<LocalDate, Int>> = error("not used")
    override suspend fun forEachRuleFacts(user: User, onMail: suspend (Uuid, MailFacts) -> Unit) = error("not used")
    override fun getYearsWithMailForUser(user: User): Flow<List<Int>> = error("not used")
    override fun getRawContent(id: Uuid): Flow<ByteArray?> = error("not used")
    override fun findDuplicate(imapAccount: ImapAccount, sent: Instant, subject: String): Flow<Uuid?> = error("not used")
    override suspend fun insert(
        imapAccount: ImapAccount,
        sender: EmailUser,
        senderName: String?,
        subject: String,
        sent: Instant,
        rawContent: ByteArray,
        textContent: String?,
        htmlContent: String?,
        isRead: Boolean,
        recipients: List<NewEmailRecipient>,
    ): Email? = error("not used")
}

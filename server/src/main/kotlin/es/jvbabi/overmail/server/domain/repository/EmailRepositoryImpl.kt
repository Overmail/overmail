package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmail
import es.jvbabi.overmail.server.database.mappers.toEmailRecipient
import es.jvbabi.overmail.server.database.mappers.toEmailTag
import es.jvbabi.overmail.server.database.mappers.toEmailUser
import es.jvbabi.overmail.server.database.mappers.toImapAccount
import es.jvbabi.overmail.server.database.mappers.toTag
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailTags
import es.jvbabi.overmail.server.database.models.EmailThreads
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Tags
import es.jvbabi.overmail.server.database.models.Threads
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailRecipient
import es.jvbabi.overmail.server.domain.models.EmailRecipientType
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.MailPage
import es.jvbabi.overmail.server.domain.models.MailParticipant
import es.jvbabi.overmail.server.domain.models.MailSummary
import es.jvbabi.overmail.server.domain.models.MailThreadRef
import es.jvbabi.overmail.server.domain.models.NewEmailRecipient
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.models.truncatedToSecond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.datetime.Date
import org.jetbrains.exposed.v1.datetime.Year
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.firstOrNull as firstRowOrNull

class EmailRepositoryImpl(
    private val database: OvermailDatabase,
    private val changes: PostgresChangeStream,
): EmailRepository {

    override fun getForImapAccount(imapAccount: ImapAccount): Flow<List<Email>> {
        return changes.changesOf(Emails, EmailRecipients, EmailUsers, ImapAccounts, Users)
            .conflate()
            .map { database.query { loadEmails(Emails.imapAccount eq imapAccount.id) } }
            .distinctUntilChanged()
    }

    override fun getById(id: Uuid): Flow<Email?> {
        return changes.changesOf(Emails, EmailRecipients, EmailUsers, ImapAccounts, Users)
            .conflate()
            .map { database.query { loadEmails(Emails.id eq id).firstOrNull() } }
            .distinctUntilChanged()
    }

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
    ): Flow<MailPage> {
        return changes
            .changesOf(
                Emails, EmailRecipients, EmailUsers, EmailTags, Tags, EmailThreads, Threads,
                ImapAccounts,
            )
            .conflate()
            .map {
                database.query {
                    loadSummaries(user, limit, after, before, newestFirst, threadId, ids, filed, archived)
                }
            }
            .distinctUntilChanged()
    }

    override fun getDailyCountsForUser(user: User, year: Int): Flow<Map<LocalDate, Int>> {
        val from = LocalDate(year, 1, 1).atStartOfDayIn(TimeZone.UTC)
        val until = LocalDate(year + 1, 1, 1).atStartOfDayIn(TimeZone.UTC)

        return changes.changesOf(Emails, ImapAccounts)
            .conflate()
            .map {
                database.query {
                    // Counted and grouped in the database rather than over loaded mails: a year of
                    // a busy mailbox is tens of thousands of rows, and all that is wanted of them
                    // is one number per day.
                    // Suppressed, not outdated: kotlinx' `Instant` is now a typealias of the one
                    // in `kotlin.time`, which makes the deprecated `Date` overload and its
                    // replacement the same signature, and the call resolves to the deprecated one.
                    @Suppress("DEPRECATION")
                    val day = Date(Emails.sent)
                    val mails = Emails.id.count()

                    (Emails innerJoin ImapAccounts)
                        .select(day, mails)
                        .where(
                            (ImapAccounts.user eq user.id) and
                                (Emails.sent greaterEq from) and
                                (Emails.sent less until)
                        )
                        .groupBy(day)
                        // Only so the answer reads as a year does; nothing depends on the order.
                        .orderBy(day, SortOrder.ASC)
                        .map { it[day] to it[mails].toInt() }
                        .toList()
                        .toMap()
                }
            }
            .distinctUntilChanged()
    }

    override fun getYearsWithMailForUser(user: User): Flow<List<Int>> {
        return changes.changesOf(Emails, ImapAccounts)
            .conflate()
            .map {
                database.query {
                    // Same deprecation as in `getDailyCountsForUser`, same reason.
                    @Suppress("DEPRECATION")
                    val year = Year(Emails.sent)

                    (Emails innerJoin ImapAccounts)
                        .select(year)
                        .where(ImapAccounts.user eq user.id)
                        .groupBy(year)
                        .orderBy(year, SortOrder.ASC)
                        .map { it[year] }
                        .toList()
                }
            }
            .distinctUntilChanged()
    }

    /** No `distinctUntilChanged` here: `ByteArray` compares by identity, so it would never drop. */
    override fun getRawContent(id: Uuid): Flow<ByteArray?> {
        return changes.changesOf(Emails)
            .conflate()
            .map {
                database.query {
                    Emails
                        .select(Emails.rawContent)
                        .where(Emails.id eq id)
                        .map { it[Emails.rawContent] }
                        .firstRowOrNull()
                }
            }
    }

    override fun findDuplicate(imapAccount: ImapAccount, sent: Instant, subject: String): Flow<Uuid?> {
        return changes.changesOf(Emails)
            .conflate()
            .map {
                database.query {
                    Emails
                        .select(Emails.id)
                        .where(
                            (Emails.imapAccount eq imapAccount.id) and
                                (Emails.sent eq sent.truncatedToSecond()) and
                                (Emails.subject eq subject)
                        )
                        .map { it[Emails.id].value }
                        .firstRowOrNull()
                }
            }
            .distinctUntilChanged()
    }

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
    ): Email? {
        val sentSecond = sent.truncatedToSecond()

        return database.query {
            // Check and insert share this transaction. The dedup key has no unique index (the
            // subject is `text` and can blow the btree key limit), so the constraint cannot do it
            // for us -- but ImporterManager keeps one importer per account, so there is no second
            // writer to race against.
            val isKnown = Emails
                .select(Emails.id)
                .where(
                    (Emails.imapAccount eq imapAccount.id) and
                        (Emails.sent eq sentSecond) and
                        (Emails.subject eq subject)
                )
                .firstRowOrNull() != null

            if (isKnown) return@query null

            val id = Emails.insertAndGetId {
                it[Emails.imapAccount] = imapAccount.id
                it[Emails.sender] = sender.id
                it[Emails.senderName] = senderName
                it[Emails.subject] = subject
                it[Emails.sent] = sentSecond
                it[Emails.rawContent] = rawContent
                it[Emails.textContent] = textContent
                it[Emails.htmlContent] = htmlContent
                it[Emails.isRead] = isRead
            }.value

            val storedRecipients = recipients
                // The unique index is (mail, address, field), so an address listed twice in the
                // same field has to collapse into one row. Sorting first lets the named entry win.
                .sortedBy { it.name == null }
                .distinctBy { it.emailUser.id to it.type }
                .map { recipient ->
                    EmailRecipient(
                        id = EmailRecipients.insertAndGetId {
                            it[EmailRecipients.email] = id
                            it[EmailRecipients.emailUser] = recipient.emailUser.id
                            it[EmailRecipients.name] = recipient.name
                            it[EmailRecipients.type] = recipient.type
                        }.value,
                        emailUser = recipient.emailUser,
                        name = recipient.name,
                        type = recipient.type,
                    )
                }

            Email(
                id = id,
                imapAccount = imapAccount,
                sender = sender,
                senderName = senderName,
                subject = subject,
                sent = sentSecond,
                textContent = textContent,
                htmlContent = htmlContent,
                isRead = isRead,
                // Nothing archives a mail on import; that only ever happens later.
                isArchived = false,
                recipients = storedRecipients,
            )
        }
    }

    /**
     * The page [getSummariesForUser] describes: how much the window holds, then the mails
     * themselves, then their recipients and tags in one lookup each. Selected column by column
     * rather than through [loadEmails], which would pull both bodies of every mail along.
     */
    private suspend fun loadSummaries(
        user: User,
        limit: Int,
        after: Instant?,
        before: Instant?,
        newestFirst: Boolean,
        threadId: Uuid?,
        ids: Collection<Uuid>?,
        filed: Boolean?,
        archived: Boolean?,
    ): MailPage {
        var where = (ImapAccounts.user eq user.id) as Op<Boolean>
        if (after != null) where = where and (Emails.sent greater after)
        if (before != null) where = where and (Emails.sent less before)
        if (threadId != null) {
            val inThread = EmailThreads
                .select(EmailThreads.email)
                .where(EmailThreads.thread eq threadId)
            where = where and (Emails.id inSubQuery inThread)
        }
        if (ids != null) where = where and (Emails.id inList ids.distinct())
        // Off the mail's own flag, not off the newest archive entry: the two say the same thing and
        // a listing must not join the history for it, see `Emails.isArchived`.
        if (archived != null) where = where and (Emails.isArchived eq archived)
        if (filed != null) {
            val inAnyThread = EmailThreads.select(EmailThreads.email)
            where = where and
                if (filed) Emails.id inSubQuery inAnyThread else Emails.id notInSubQuery inAnyThread
        }

        // Counted rather than derived from the rows: the point of the number is to say how much
        // is behind the page, which the page itself cannot know.
        val mailCount = Emails.id.count()
        val total = (Emails innerJoin ImapAccounts)
            .select(mailCount)
            .where(where)
            .map { it[mailCount].toInt() }
            .firstRowOrNull() ?: 0

        val order = if (newestFirst) SortOrder.DESC else SortOrder.ASC

        // Joined over the sender reference of `Emails`, so the address comes along without a
        // second lookup; the recipients below need one because there are many per mail.
        val rows = (Emails innerJoin ImapAccounts innerJoin EmailUsers)
            .select(
                Emails.id, Emails.subject, Emails.sent, Emails.senderName, Emails.isRead,
                Emails.isArchived, EmailUsers.address,
            )
            .where(where)
            // The id only breaks ties, and turns around with the send time so that reading the
            // mailbox from the other end walks the same order backwards. Send times are stored at
            // second precision, so a page can well end in the middle of a second, and without a
            // second key the rows of that second would come back in a different order per query --
            // a caller paging through the mailbox would see one of them twice and another not at
            // all.
            .orderBy(Emails.sent to order, Emails.id to order)
            .limit(limit)
            .toList()

        if (rows.isEmpty()) return MailPage(mails = emptyList(), total = total)

        val ids = rows.map { it[Emails.id].value }

        val recipients = (EmailRecipients innerJoin EmailUsers)
            .select(EmailRecipients.email, EmailRecipients.name, EmailRecipients.type, EmailUsers.address)
            .where(EmailRecipients.email inList ids)
            .toList()
            .groupBy { it[EmailRecipients.email].value }

        val threads = loadThreads(ids)

        // Every tag of the mail, filed by the agent or by the user: a listing shows how a mail
        // ended up filed, not who did the filing.
        val tags = (EmailTags innerJoin Tags)
            .selectAll()
            .where(EmailTags.email inList ids)
            .toList()
            // The mails are the caller's own, so every tag on them is theirs as well.
            .groupBy({ it[EmailTags.email].value }) { it.toEmailTag(it.toTag(user)) }

        val mails = rows.map { row ->
            val id = row[Emails.id].value
            val addressed = recipients[id].orEmpty().groupBy(
                { it[EmailRecipients.type] },
                { MailParticipant(address = it[EmailUsers.address], name = it[EmailRecipients.name]) },
            )

            MailSummary(
                id = id,
                subject = row[Emails.subject],
                sent = row[Emails.sent],
                sender = MailParticipant(
                    address = row[EmailUsers.address],
                    name = row[Emails.senderName],
                ),
                recipients = addressed[EmailRecipientType.RECIPIENT].orEmpty(),
                cc = addressed[EmailRecipientType.CC].orEmpty(),
                bcc = addressed[EmailRecipientType.BCC].orEmpty(),
                isRead = row[Emails.isRead],
                isArchived = row[Emails.isArchived],
                thread = threads[id],
                tags = tags[id].orEmpty(),
            )
        }

        return MailPage(mails = mails, total = total)
    }

    /**
     * The matter each of [ids] sits in, absent for a mail nothing has filed. A mail may sit in
     * several; the first one wins, which is all a listing can show anyway.
     *
     * The sizes come from a second lookup rather than from a join on the first: the point of the
     * number is how big the thread is, and a join would only ever count the part of it that is on
     * this page.
     */
    private suspend fun loadThreads(ids: List<Uuid>): Map<Uuid, MailThreadRef> {
        val memberships = (EmailThreads innerJoin Threads)
            .select(EmailThreads.email, Threads.id, Threads.title)
            .where(EmailThreads.email inList ids)
            .map { Triple(it[EmailThreads.email].value, it[Threads.id].value, it[Threads.title]) }
            .toList()

        if (memberships.isEmpty()) return emptyMap()

        val entries = EmailThreads.id.count()
        val sizes = EmailThreads
            .select(EmailThreads.thread, entries)
            .where(EmailThreads.thread inList memberships.map { it.second }.distinct())
            .groupBy(EmailThreads.thread)
            .map { it[EmailThreads.thread].value to it[entries].toInt() }
            .toList()
            .toMap()

        return memberships.associate { (mail, thread, title) ->
            mail to MailThreadRef(id = thread, title = title, size = sizes[thread] ?: 1)
        }
    }

    /**
     * Resolves the mails matching [where] together with their account, sender and recipients.
     * Done as separate lookups per referenced table instead of one big join, because both the
     * account and the email users hang off [Users] and joining it twice would need aliases.
     */
    private suspend fun loadEmails(where: Op<Boolean>): List<Email> {
        val rows = Emails
            .selectAll()
            .where(where)
            .orderBy(Emails.sent, SortOrder.DESC)
            .toList()

        if (rows.isEmpty()) return emptyList()

        val imapAccounts = (ImapAccounts innerJoin Users)
            .selectAll()
            .where(ImapAccounts.id inList rows.map { it[Emails.imapAccount].value }.distinct())
            .map { it.toImapAccount(it.toUser()) }
            .toList()
            .associateBy { it.id }

        val senders = (EmailUsers innerJoin Users)
            .selectAll()
            .where(EmailUsers.id inList rows.map { it[Emails.sender].value }.distinct())
            .map { it.toEmailUser(it.toUser()) }
            .toList()
            .associateBy { it.id }

        val recipients = (EmailRecipients innerJoin EmailUsers innerJoin Users)
            .selectAll()
            .where(EmailRecipients.email inList rows.map { it[Emails.id].value })
            .toList()
            .groupBy({ it[EmailRecipients.email].value }) { it.toEmailRecipient(it.toEmailUser(it.toUser())) }

        return rows.map { row ->
            row.toEmail(
                imapAccount = imapAccounts.getValue(row[Emails.imapAccount].value),
                sender = senders.getValue(row[Emails.sender].value),
                recipients = recipients[row[Emails.id].value].orEmpty(),
            )
        }
    }
}

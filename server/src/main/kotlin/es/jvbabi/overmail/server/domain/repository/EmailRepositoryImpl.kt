package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.changes.PostgresChangeStream
import es.jvbabi.overmail.server.database.mappers.toEmail
import es.jvbabi.overmail.server.database.mappers.toEmailRecipient
import es.jvbabi.overmail.server.database.mappers.toEmailUser
import es.jvbabi.overmail.server.database.mappers.toImapAccount
import es.jvbabi.overmail.server.database.mappers.toUser
import es.jvbabi.overmail.server.database.models.EmailRecipients
import es.jvbabi.overmail.server.database.models.EmailUsers
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.database.models.Users
import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailRecipient
import es.jvbabi.overmail.server.domain.models.EmailRecipientType
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.truncatedToSecond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
        subject: String,
        sent: Instant,
        rawContent: ByteArray,
        textContent: String?,
        htmlContent: String?,
        isRead: Boolean,
        recipients: List<Pair<EmailUser, EmailRecipientType>>,
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
                it[Emails.subject] = subject
                it[Emails.sent] = sentSecond
                it[Emails.rawContent] = rawContent
                it[Emails.textContent] = textContent
                it[Emails.htmlContent] = htmlContent
                it[Emails.isRead] = isRead
            }.value

            val storedRecipients = recipients
                .distinctBy { (emailUser, type) -> emailUser.id to type }
                .map { (emailUser, type) ->
                    EmailRecipient(
                        id = EmailRecipients.insertAndGetId {
                            it[EmailRecipients.email] = id
                            it[EmailRecipients.emailUser] = emailUser.id
                            it[EmailRecipients.type] = type
                        }.value,
                        emailUser = emailUser,
                        type = type,
                    )
                }

            Email(
                id = id,
                imapAccount = imapAccount,
                sender = sender,
                subject = subject,
                sent = sentSecond,
                textContent = textContent,
                htmlContent = htmlContent,
                isRead = isRead,
                recipients = storedRecipients,
            )
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

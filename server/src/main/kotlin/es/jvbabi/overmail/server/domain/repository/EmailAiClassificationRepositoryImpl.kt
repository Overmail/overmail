package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.database.OvermailDatabase
import es.jvbabi.overmail.server.database.mappers.asStoredHistory
import es.jvbabi.overmail.server.database.mappers.toEmailAiClassification
import es.jvbabi.overmail.server.database.models.EmailAiClassifications
import es.jvbabi.overmail.server.database.models.EmailAiClassifications as AiClassifications
import es.jvbabi.overmail.server.database.models.Emails
import es.jvbabi.overmail.server.database.models.ImapAccounts
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.EmailAiClassification
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

class EmailAiClassificationRepositoryImpl(
    private val database: OvermailDatabase,
) : EmailAiClassificationRepository {

    override suspend fun record(
        emailId: Uuid,
        reason: ClassificationReason,
        history: List<AgentLine>,
        tokensIn: Int?,
        tokensOut: Int?,
        provider: String,
        model: String,
        fastModel: String?,
        startedAt: Instant,
        finishedAt: Instant,
    ): EmailAiClassification {
        val stored = history.asStoredHistory()

        val id = database.query {
            EmailAiClassifications.insertAndGetId {
                it[EmailAiClassifications.email] = emailId
                it[EmailAiClassifications.reason] = reason
                it[EmailAiClassifications.history] = stored
                it[EmailAiClassifications.tokensIn] = tokensIn
                it[EmailAiClassifications.tokensOut] = tokensOut
                it[EmailAiClassifications.provider] = provider
                it[EmailAiClassifications.model] = model
                it[EmailAiClassifications.fastModel] = fastModel
                it[EmailAiClassifications.startedAt] = startedAt
                it[EmailAiClassifications.finishedAt] = finishedAt
            }.value
        }

        return EmailAiClassification(
            id = id,
            emailId = emailId,
            reason = reason,
            history = history,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            provider = provider,
            model = model,
            fastModel = fastModel,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
    }

    override suspend fun unclassifiedMails(user: User, limit: Int): List<Uuid> {
        return database.query {
            // "Not among the mails that have a run", which is what the absence of a classification
            // looks like in one query rather than as two lists compared in Kotlin. A subquery rather
            // than a left join and a null check: the join would produce a row per run for the mails
            // that do have one, and then have to throw them away again.
            val classified = AiClassifications.select(AiClassifications.email)

            (Emails innerJoin ImapAccounts)
                .select(Emails.id, Emails.sent)
                .where((ImapAccounts.user eq user.id) and (Emails.id notInSubQuery classified))
                .orderBy(Emails.sent to SortOrder.DESC, Emails.id to SortOrder.DESC)
                .limit(limit)
                .map { it[Emails.id].value }
                .toList()
        }
    }

    override suspend fun getForEmail(emailId: Uuid): List<EmailAiClassification> {
        return database.query {
            EmailAiClassifications
                .selectAll()
                .where(EmailAiClassifications.email eq emailId)
                // The id only breaks ties: two runs started in the same moment still have an order.
                .orderBy(
                    EmailAiClassifications.startedAt to SortOrder.DESC,
                    EmailAiClassifications.id to SortOrder.DESC,
                )
                .map { it.toEmailAiClassification() }
                .toList()
        }
    }
}

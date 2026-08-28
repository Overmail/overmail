package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.ai.AgentLine
import es.jvbabi.overmail.server.domain.models.ClassificationReason
import es.jvbabi.overmail.server.domain.models.EmailAiClassification
import es.jvbabi.overmail.server.domain.models.User
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface EmailAiClassificationRepository {
    /**
     * Keeps one run: what was said, what it cost, where it ran and how long it took.
     *
     * Written once, at the end, and never updated. A run is over when it is stored -- whether it
     * finished, failed or was hung up on -- and a row that could still change would be a row nobody
     * could compare to the next one.
     *
     * Nothing is deduplicated. The same mail read twice is two runs, because the interesting
     * question about the second one is how it differs from the first.
     */
    suspend fun record(
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
    ): EmailAiClassification

    /** Every run over this mail, the newest first. Empty for a mail nothing has read yet. */
    suspend fun getForEmail(emailId: Uuid): List<EmailAiClassification>

    /**
     * The newest mails of [user] that no run has ever touched, at most [limit] of them.
     *
     * "Never touched" and not "never successfully classified": a mail whose run fell over has a row,
     * so it is not in here. That is the honest reading of the question this answers -- which mails
     * has the agent not looked at -- and it is what keeps a mail that fails every time from being
     * offered again on every press of the button.
     */
    suspend fun unclassifiedMails(user: User, limit: Int): List<Uuid>
}

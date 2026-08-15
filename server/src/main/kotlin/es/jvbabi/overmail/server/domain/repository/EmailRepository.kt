package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailRecipientType
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface EmailRepository {
    fun getForImapAccount(imapAccount: ImapAccount): Flow<List<Email>>
    fun getById(id: Uuid): Flow<Email?>

    /** Loads the raw source on demand, see [Email]. */
    fun getRawContent(id: Uuid): Flow<ByteArray?>

    /**
     * Emits the id of an already imported mail with the same account, send second and subject,
     * which is how the importer recognises mails it has seen before. [sent] is truncated to
     * whole seconds by this call.
     */
    fun findDuplicate(imapAccount: ImapAccount, sent: Instant, subject: String): Flow<Uuid?>

    /**
     * Stores the mail together with its recipient links, or returns null and writes nothing if
     * [findDuplicate] already knows it. Never updates an existing mail: the local state (`is_read`)
     * is ours, the server's copy must not overwrite it.
     *
     * [sent] is truncated to whole seconds, [recipients] deduplicated per email user and type.
     */
    suspend fun insert(
        imapAccount: ImapAccount,
        sender: EmailUser,
        subject: String,
        sent: Instant,
        rawContent: ByteArray,
        textContent: String?,
        htmlContent: String?,
        isRead: Boolean,
        recipients: List<Pair<EmailUser, EmailRecipientType>>,
    ): Email?
}

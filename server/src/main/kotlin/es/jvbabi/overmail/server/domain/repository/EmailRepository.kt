package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.NewEmailRecipient
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface EmailRepository {
    fun getForImapAccount(imapAccount: ImapAccount): Flow<List<Email>>
    fun getById(id: Uuid): Flow<Email?>

    /**
     * Ids of every stored mail, oldest send time first. Ids rather than mails: a consumer that
     * works through the whole mailbox would otherwise pull every sender and recipient along on
     * each emission, and it only needs to know what is there and in which order.
     */
    fun getAllIdsOldestFirst(): Flow<List<Uuid>>

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
     * [sent] is truncated to whole seconds, [recipients] deduplicated per email user and type,
     * keeping the named entry of an address that appears both with and without a display name.
     */
    suspend fun insert(
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
    ): Email?
}

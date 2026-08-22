package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.MailPage
import es.jvbabi.overmail.server.domain.models.NewEmailRecipient
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface EmailRepository {
    fun getForImapAccount(imapAccount: ImapAccount): Flow<List<Email>>
    fun getById(id: Uuid): Flow<Email?>

    /**
     * At most [limit] mails of [user], counted over every account they have. [after] and [before]
     * cut the window down and are both exclusive, so the send time of the last mail of a page can
     * be handed back as the cursor for the next one.
     *
     * [newestFirst] turns the page around, which is how a caller reaches the far end of a long
     * mailbox without walking through everything in front of it: ordering the other way makes the
     * oldest mails the first page, and [before] becomes [after].
     *
     * [threadId] narrows the window to one matter, [ids] to a named handful and [filed] to the
     * mails that sit in some thread or in none. None of the three is a stretch of the list -- a
     * thread's mails sit wherever they were sent -- which is what they are for: a caller that
     * knows what it wants asks for exactly that instead of walking there. Anything belonging to
     * another user matches nothing, like any mail that is not [user]'s.
     *
     * Summaries rather than [Email]s, see [es.jvbabi.overmail.server.domain.models.MailSummary]:
     * a listing wants the headers, not the bodies. The page reports how many mails the window
     * holds in total, counted in the same transaction so it cannot disagree with the rows.
     */
    fun getSummariesForUser(
        user: User,
        limit: Int,
        after: Instant? = null,
        before: Instant? = null,
        newestFirst: Boolean = true,
        threadId: Uuid? = null,
        ids: Collection<Uuid>? = null,
        filed: Boolean? = null,
    ): Flow<MailPage>

    /**
     * How many mails of [user] arrived on each day of [year], counted over every account they
     * have. A day nothing arrived on is absent rather than held at zero: the caller draws the year
     * either way, so a zero entry would only be a second way of saying the same thing.
     *
     * Days are UTC days, and so is the year they are cut out of -- the send time carries no zone
     * of its own, and picking the viewer's would make the same mailbox count differently per
     * reader.
     */
    fun getDailyCountsForUser(user: User, year: Int): Flow<Map<LocalDate, Int>>

    /**
     * The years [user] has mail in at all, oldest first, so a caller can offer the years there is
     * something to show for instead of guessing at a range. UTC years, as in
     * [getDailyCountsForUser].
     */
    fun getYearsWithMailForUser(user: User): Flow<List<Int>>

    /**
     * Ids of the mails the AI has not worked through yet, oldest send time first. Ids rather than
     * mails: a consumer that works through the whole mailbox would otherwise pull every sender and
     * recipient along on each emission, and it only needs to know what is there and in which order.
     *
     * A mail leaves this list once [markAiProcessed] stamped it, so a newly imported mail simply
     * appears at the end of the next emission.
     */
    fun getUnprocessedIdsOldestFirst(): Flow<List<Uuid>>

    /** Stamps the mail as worked through by the AI, with the current time. */
    suspend fun markAiProcessed(id: Uuid)

    /**
     * Takes [markAiProcessed]'s stamp off every mail, which puts the whole mailbox back into
     * [getUnprocessedIdsOldestFirst]. Returns how many mails carried one.
     */
    suspend fun clearAiProcessing(): Int

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

package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How a magic mail lets its reader in. */
enum class MagicEmailKind {
    /** A one-time code to type somewhere else: "Your code is 418 902". */
    CODE,

    /** A link that signs the reader in when it is opened. */
    LINK,
}

/**
 * One way into somewhere that a mail carries, see
 * [es.jvbabi.overmail.server.database.models.MagicEmails].
 *
 * One per kind, so the mail that writes a code out and offers a link beside it is two of these.
 *
 * The mail's id rather than the mail, unlike [SpamEntry] which carries neither: these are asked for
 * per mail as well as across the mailbox -- "what can I still use" is a list over several mails --
 * so the one they belong to has to be nameable.
 *
 * No provider icon on it. The column is there and nothing fills it yet: an icon is fetched from a
 * third party, which is [es.jvbabi.overmail.server.domain.repository.icon]'s business rather than
 * something a mail is read for.
 */
data class MagicEmail(
    val id: Uuid,
    val emailId: Uuid,
    /** Who it lets the reader into, as a name: "GitHub", "Notion", "Steam". */
    val provider: String,
    val kind: MagicEmailKind,
    /**
     * The thing itself: the code as the mail wrote it, or the whole link, going by [kind].
     *
     * Never empty, and the reason a row exists at all -- a way in nobody can read is not one, see
     * [es.jvbabi.overmail.server.database.models.MagicEmails.payload].
     */
    val payload: String,
    /** When it stops working as the mail stated it, null for the mail that never said. */
    val validUntil: Instant?,
    /** When the reader marked it used, null while they have not. */
    val usedAt: Instant?,
    /** When the row was written, which is not when the mail arrived. */
    val createdAt: Instant,
)

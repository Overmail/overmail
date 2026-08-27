package es.jvbabi.overmail.server.database.models

import es.jvbabi.overmail.server.domain.models.MagicEmailKind
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * A mail that exists to let its reader in somewhere: a one-time code, or a link that signs them in.
 *
 * Its own table rather than columns on [Emails], because most mail is not one of these and a table
 * of them is the list a screen wants -- "what can I still use" is a query over these rows, not a
 * scan of the mailbox with a filter on it.
 *
 * The row is about the mail, not about the reader's session: nothing here is a secret to guard, the
 * code and the link stand in the mail itself either way. What is here is what makes the mail
 * usable -- who it lets you into, in what form, and until when.
 */
object MagicEmails : UuidTable("magic_emails") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** Who the code or link lets the reader into, as a name: "GitHub", "Notion", "Steam". */
    val provider = varchar("provider", 128)

    /**
     * The provider's icon, as bytes, null where none was found.
     *
     * Inline rather than a reference to [EmailAvatars] as a correspondent's picture is: a provider
     * icon is not the sender's picture -- the mail may well come from a mail service nobody would
     * recognise -- and there is one per row rather than one per address to be shared.
     *
     * Nullable because the row is worth having without it: a code that still works is a code that
     * still works, whether or not anybody could find a logo for the service it belongs to.
     */
    val providerIcon = binary("provider_icon").nullable()

    val kind = enumerationByName<MagicEmailKind>("kind", 16)

    /**
     * The thing itself: the code as the mail writes it, or the whole link, going by [kind].
     *
     * The column that makes the row worth anything to a reader. Without it a row says a mail
     * carries a code somewhere and leaves them to go and find it -- which is the mailbox they were
     * trying not to open. With it a screen can put the code under a copy button and the link under
     * something to press.
     *
     * Not null, and no row without one: a code nobody can read is not a way in. A mail whose code
     * could not be copied out writes no row at all rather than an empty one -- see how the agent
     * decides which rows to write.
     *
     * `text` rather than a bounded string because of the links. A sign-in link carries the whole
     * grant in its query -- a signed token, a session id, a return path -- and runs to several
     * hundred characters; a code is a dozen at most, and there is no length that fits both and
     * still means something.
     *
     * Kept as the mail wrote it, spaces in a code included: "418 902" is how the reader will see it
     * next to the field they are typing into, and a screen can strip the space far more safely than
     * it could put one back.
     */
    val payload = text("payload")

    /**
     * When the code or link stops working, as the mail itself states it, null where it does not.
     *
     * Nullable because plenty of these mails never say. The alternative is a made-up moment, and a
     * row claiming a code dies at half past two when nobody knows that is worse than a row that
     * admits it: a screen can grey out what has expired, and it can say nothing about what has no
     * stated end -- it cannot un-know a time it was told.
     */
    val validUntil = timestamp("valid_until").nullable()

    /**
     * When the reader used it, null while they have not.
     *
     * Their own record and nobody else's: whether a code was really typed in somewhere is not
     * something a mailbox can know. It is here so a used code can be told from an unused one on
     * screen without the reader having to remember.
     */
    val usedAt = timestamp("used_at").nullable()

    /** When the row was written, which is not when the mail arrived. */
    val createdAt = timestamp("created_at")

    init {
        // One row per mail and kind. A mail carrying a code *and* a link -- which is the usual
        // shape of these -- is two rows, one each; a mail carrying two codes is not a thing, and
        // an extractor run twice over the same mail must not make it one.
        uniqueIndex(email, kind)
    }
}

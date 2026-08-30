package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Instant
import kotlin.uuid.Uuid

object Emails : UuidTable("emails") {
    /** The account the mail was imported through; also determines who owns it. */
    val imapAccount = reference("imap_account_id", ImapAccounts, onDelete = ReferenceOption.CASCADE)
    val sender = reference("sender_id", EmailUsers)

    /**
     * Display name the sender used in *this* mail, absent for a bare `foo@bar.tld` address. Stored
     * per mail and not on [EmailUsers], see there.
     */
    val senderName = varchar("sender_name", 255).nullable()

    val subject = text("subject")

    /** Send time, stored truncated to whole seconds so it can serve as a dedup key. */
    val sent = timestamp("sent")

    /** The untouched RFC 5322 source as it came off the wire. */
    val rawContent = binary("raw_content")

    val textContent = text("text_content").nullable()
    val htmlContent = text("html_content").nullable()

    val isRead = bool("is_read").default(false)

    init {
        /**
         * Narrows the dedup lookup (account + send second + subject) to a handful of rows. The
         * subject is left out: as `text` it can exceed the btree key limit.
         */
        index(false, imapAccount, sent)
    }
}

/**
 * A stored mail. Loading one through this entity reads [Emails.rawContent] with it, which can be
 * megabytes -- a listing that does not need the source should select its columns through the DSL
 * instead, see `http/stack/stackSocket.kt`.
 */
class Email(id: EntityID<Id>) : UuidEntity(id) {
    companion object : UuidEntityClass<Email>(Emails)
    typealias Id = Uuid

    var imapAccount by ImapAccount referencedOn Emails.imapAccount
    var sender by EmailUser referencedOn Emails.sender
    var senderName by Emails.senderName
    var subject by Emails.subject
    var sent by Emails.sent
    var rawContent by Emails.rawContent
    var textContent by Emails.textContent
    var htmlContent by Emails.htmlContent
    var isRead by Emails.isRead

    val recipients by EmailRecipient referrersOn EmailRecipients.email
    val aiClassificationEvents by EmailAiClassificationEvent referrersOn EmailAiClassificationEvents.email
    val labels by EmailLabel referrersOn EmailLabels.email
}

/**
 * Mails are deduplicated by account, send second and subject, so send times are stored and looked
 * up at second precision.
 */
fun Instant.truncatedToSecond(): Instant = Instant.fromEpochSeconds(epochSeconds)

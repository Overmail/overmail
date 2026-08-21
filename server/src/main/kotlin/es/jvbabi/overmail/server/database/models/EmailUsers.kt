package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable

/**
 * A person appearing in a mail header (sender or recipient). Scoped per [Users] row: the same
 * address imported by two users yields two rows, so no user can see the other's address book.
 *
 * Identity only, no display name: senders reuse one address for many names (every GitHub
 * notification arrives as `notifications@github.com` with the acting username as its name), so the
 * name belongs to the single mail it came from -- see [Emails.senderName] and [EmailRecipients.name].
 */
object EmailUsers : UuidTable("email_users") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    val address = varchar("address", 320)

    init {
        uniqueIndex(user, address)
    }
}

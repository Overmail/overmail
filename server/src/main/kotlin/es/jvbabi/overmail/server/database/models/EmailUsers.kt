package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable

/**
 * A person appearing in a mail header (sender or recipient). Scoped per [Users] row: the same
 * address imported by two users yields two rows, so no user can see the other's address book.
 */
object EmailUsers : UuidTable("email_users") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /** Display name from the header, absent for bare `foo@bar.tld` addresses. */
    val name = varchar("name", 255).nullable()
    val address = varchar("address", 320)

    init {
        uniqueIndex(user, address)
    }
}

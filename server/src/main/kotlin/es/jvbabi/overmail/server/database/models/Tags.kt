package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * A label a user files mails under. Scoped per [Users] row like the address book is: two users who
 * both tag something "Rechnung" own one row each, and neither sees the other's.
 */
object Tags : UuidTable("tags") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    val name = varchar("name", 128)

    /** What the tag stands for, for a user browsing their own tags. Not filled in yet. */
    val description = text("description").nullable()

    val createdAt = timestamp("created_at")

    /** False for a tag the user made themselves. */
    val createdByAgent = bool("created_by_agent")

    init {
        // One row per name and user: the queue looks a tag up before it creates it, and two mails
        // tagged in the same second must not end up with two "Rechnung".
        uniqueIndex(user, name)
    }
}

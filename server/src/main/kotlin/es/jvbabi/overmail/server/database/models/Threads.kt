package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/** A matter several mails belong to, scoped per [Users] row like tags and the address book. */
object Threads : UuidTable("threads") {
    val user = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)

    /** What the matter is called. May be sharpened once more mails join. */
    val title = varchar("title", 255)

    val createdAt = timestamp("created_at")

    /** False for a thread the user opened themselves. */
    val createdByAgent = bool("created_by_agent")
}

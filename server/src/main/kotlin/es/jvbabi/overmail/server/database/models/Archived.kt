package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Every time a mail was put into the archive or taken back out of it, one row per change. Taking a
 * mail back out appends a row with [isArchived] false rather than removing the one that put it in:
 * the table is the record of what happened to the mail, and a row that can be deleted records
 * nothing.
 *
 * The current state is *not* read from here -- [Emails.isArchived] carries it, so a listing does
 * not have to reach for the newest row per mail on every query.
 */
object Archived : UuidTable("archived") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)

    /** True for the change that archived the mail, false for the one that took it back out. */
    val isArchived = bool("is_archived")

    val createdAt = timestamp("created_at")

    /** False for a change a user made themselves. Nothing sets this yet, see the repository. */
    val createdByAgent = bool("created_by_agent")

    init {
        // The history of one mail, in order: that is the only way this table is read.
        index(false, email, createdAt)
    }
}

package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/** Puts an [Emails] row into a [Threads] row. */
object EmailThreads : UuidTable("email_threads") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val thread = reference("thread_id", Threads, onDelete = ReferenceOption.CASCADE)

    /** Why this mail belongs to that matter. Absent when a user put it there. */
    val reason = text("reason").nullable()

    val createdAt = timestamp("created_at")

    /** False for a mail a user filed themselves. */
    val createdByAgent = bool("created_by_agent")

    init {
        // Per thread, not per mail: a mail sitting in two matters is unusual but not wrong, and a
        // unique index on the mail alone would be a decision this table does not have to make.
        uniqueIndex(email, thread)
    }
}

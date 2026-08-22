package es.jvbabi.overmail.server.database.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestamp

/** Files an [Emails] row under a [Tags] row. */
object EmailTags : UuidTable("email_tags") {
    val email = reference("email_id", Emails, onDelete = ReferenceOption.CASCADE)
    val tag = reference("tag_id", Tags, onDelete = ReferenceOption.CASCADE)

    /**
     * Why this mail carries this tag, in the words of whoever attached it. Absent when a user
     * simply picked the tag.
     */
    val reason = text("reason").nullable()

    val createdAt = timestamp("created_at")

    /** False for a tag a user attached themselves. */
    val createdByAgent = bool("created_by_agent")

    init {
        uniqueIndex(email, tag)
    }
}

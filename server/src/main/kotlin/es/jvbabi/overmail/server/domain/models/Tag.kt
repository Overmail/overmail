package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A label [user] files mails under. */
data class Tag(
    val id: Uuid,
    val user: User,
    val name: String,
    /** What the tag stands for, as opposed to why one mail carries it. */
    val description: String?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

/** One mail filed under one [tag]. */
data class EmailTag(
    val id: Uuid,
    val tag: Tag,
    /** Why this mail carries the tag, absent when it was simply picked. */
    val reason: String?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

/**
 * A mail in the neighbourhood of the one being worked on: enough of it to see what it was and how
 * it ended up filed, without loading its senders and recipients.
 */
data class TaggedMail(
    val id: Uuid,
    val subject: String,
    val sent: Instant,
    /** Address the mail came from. */
    val sender: String,
    /** The opening of the mail, enough to tell what it was. Absent for a mail without a text part. */
    val excerpt: String?,
    val tags: List<EmailTag>,
)

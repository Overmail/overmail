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

/**
 * A tag and how much of the mailbox is under it.
 *
 * The count is what tells a well-used label from one that was made once and never again -- which is
 * the difference between "file this under the word the mailbox already uses" and "the mailbox has a
 * near-miss of that word lying around". A tag nothing carries is still a tag: a reader may have just
 * made it.
 */
data class TagUsage(val tag: Tag, val mails: Int)

/** One mail filed under one [tag]. */
data class EmailTag(
    val id: Uuid,
    val tag: Tag,
    /** Why this mail carries the tag, absent when it was simply picked. */
    val reason: String?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

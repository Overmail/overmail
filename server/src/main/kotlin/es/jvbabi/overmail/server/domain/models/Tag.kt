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

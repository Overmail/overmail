package es.jvbabi.overmail.server.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A matter several mails belong to. Named [MailThread] rather than `Thread`: the JVM already has
 * one of those, and a domain type that shadows it makes for confusing reading.
 */
data class MailThread(
    val id: Uuid,
    val user: User,
    val title: String,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

/** One mail sitting in one [thread]. */
data class MailThreadEntry(
    val id: Uuid,
    val thread: MailThread,
    /** Why this mail belongs there, absent when a user put it there. */
    val reason: String?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

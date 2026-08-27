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
    /**
     * The identifier the matter goes by, where it has one, see
     * [es.jvbabi.overmail.server.database.models.Threads.identifier]. Null for a thread that is
     * held together by having been picked rather than by a number.
     */
    val identifier: String?,
    val createdAt: Instant,
    val createdByAgent: Boolean,
)

/**
 * A thread as an overview lists it: what it is, which mails are in it and when it last saw
 * traffic. The mails themselves are not in here -- a list of a thousand threads would be the whole
 * mailbox -- but their ids are, which is enough to lay a list out and fetch what is on screen.
 */
data class ThreadOverview(
    val thread: MailThread,
    /** Its mails, newest first, in the order a list shows them. */
    val mailIds: List<Uuid>,
    /** When the newest of them was sent, which is what threads are ranked by. */
    val lastSentAt: Instant,
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

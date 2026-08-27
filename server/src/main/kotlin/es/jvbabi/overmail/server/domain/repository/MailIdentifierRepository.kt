package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.MailIdentifier
import es.jvbabi.overmail.server.domain.models.User
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface MailIdentifierRepository {
    /**
     * Writes down that the mail names [identifier]. Returns null and writes nothing where it is
     * already written down.
     *
     * The rerun is the normal case: the same mail is read again whenever somebody asks, and what was
     * written first stands. A second reading that spells the number differently is not news -- it is
     * the same mail, and the string other mails will be matched against must not move under them.
     */
    suspend fun record(emailId: Uuid, identifier: String): MailIdentifier?

    /** What this mail names as its matter, or null where nothing read one off it. */
    suspend fun identifierOf(emailId: Uuid): String?

    /**
     * The mails of [user] that name [identifier], oldest first, at most [limit].
     *
     * Oldest first because that is the order a matter happened in, and a thread being opened for it
     * is opened around its first mail. [before] leaves out everything from that moment on, which is
     * how a run asks what the mailbox already knew.
     *
     * Matched without regard to case: a sender writing "re-2024-00123" this time means the invoice
     * it wrote as "RE-2024-00123" last time.
     */
    suspend fun mailsWith(
        user: User,
        identifier: String,
        before: Instant? = null,
        limit: Int = 50,
    ): List<Uuid>
}

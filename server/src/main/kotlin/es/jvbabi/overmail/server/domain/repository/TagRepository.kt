package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TagUsage
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TagRepository {
    fun getForUser(user: User): Flow<List<Tag>>

    /** What a mail is filed under, with the reason each tag was attached for. */
    fun getForEmail(email: Email): Flow<List<EmailTag>>

    /**
     * Every tag of [user] with the number of mails under it.
     *
     * The whole list at once, which is what it is for: it is read to find the label the mailbox
     * already uses for something before a second spelling of it is made up, and that comparison is
     * over all of them. A private mailbox has tens of tags, not thousands.
     */
    suspend fun usageForUser(user: User): List<TagUsage>

    /**
     * The user's tag by that name, created if they do not have it yet. Matched without regard to
     * case, so "Rechnung" and "rechnung" stay one tag; the spelling of the first writer is kept.
     */
    suspend fun findOrCreate(user: User, name: String, createdByAgent: Boolean): Tag

    /**
     * Files the mail under [tag]. Returns null and writes nothing when the mail already carries it:
     * a second run must not overwrite the reason a user wrote with one the agent came up with.
     */
    suspend fun attach(emailId: Uuid, tag: Tag, reason: String?, createdByAgent: Boolean): EmailTag?

    /**
     * Takes the mail out from under [tag]. The tag itself stays: it is the user's label, and they
     * may well have other mail under it -- and an empty one is still a tag they made.
     *
     * Returns whether the mail was filed under it at all, so a caller can tell a removal from a
     * no-op.
     *
     * With [onlyIfAgentAttached], a filing the user made themselves is left alone and the call
     * reports that it removed nothing. That is what the agent detaches with, and it is here rather
     * than in the caller because it is the kind of rule that has to hold even when somebody forgets
     * to check: the agent may take back its own filing and nobody else's.
     */
    suspend fun detach(emailId: Uuid, tag: Tag, onlyIfAgentAttached: Boolean = false): Boolean

    /**
     * The mails of [user] filed under any of [names], newest first, that arrived before [before].
     *
     * Ids and not summaries: what a caller does with them is look them up through
     * [EmailRepository.getSummariesForUser], which is where the tags and the threads come from
     * anyway. Names are matched without regard to case, as [findOrCreate] does.
     *
     * Only mail that is older than [before], because that is the question being asked: what did the
     * mailbox already know when this mail arrived. A mail is at most once in the answer however many
     * of the names it carries.
     */
    suspend fun mailsUnderTags(
        user: User,
        names: Collection<String>,
        before: Instant,
        limit: Int,
    ): List<Uuid>
}

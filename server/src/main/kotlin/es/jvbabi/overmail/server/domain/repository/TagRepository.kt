package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.TaggedMail
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

interface TagRepository {
    fun getForUser(user: User): Flow<List<Tag>>

    /** What a mail is filed under, with the reason each tag was attached for. */
    fun getForEmail(email: Email): Flow<List<EmailTag>>

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
     * Unfiles a mail the agent had filed. A tag a user attached themselves is left alone, and so is
     * the tag itself -- other mails may still be filed under it. Returns whether a row went.
     */
    suspend fun detachAgentTag(emailId: Uuid, tagId: Uuid): Boolean

    /**
     * Throws away what the agent filed: its links first, then the tags it created that nothing
     * points at any more. Filing a user did themselves survives, including their link to a tag the
     * agent had invented. Returns how many rows went.
     */
    suspend fun clearAgentWork(): ClearedAgentWork

    /**
     * The [limit] most recently sent mails of [user] from before [before] that neighbour a mail,
     * newest first. A mail is a neighbour when it is filed under any of [tagNames] -- identifiers
     * need no lookup of their own, they are tags as well -- or when its subject is [subject] with
     * the reply and forward prefixes taken off, which is what a conversation looks like from the
     * outside.
     */
    fun findNeighbours(
        user: User,
        tagNames: Collection<String>,
        subject: String,
        before: Instant,
        limit: Int,
    ): Flow<List<TaggedMail>>
}

/** How much a `clearAgentWork` threw away: the mails it unfiled, and what it removed outright. */
data class ClearedAgentWork(
    val links: Int,
    val created: Int,
)

package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.Email
import es.jvbabi.overmail.server.domain.models.EmailTag
import es.jvbabi.overmail.server.domain.models.Tag
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
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
}

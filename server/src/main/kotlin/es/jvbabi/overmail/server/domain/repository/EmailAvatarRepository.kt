package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.EmailAvatar
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/** The avatar cache: the pictures we hold for correspondents, and what points at them. */
interface EmailAvatarRepository {

    /**
     * Every picture reachable from [user]'s address book, once per address pointing at it.
     * Addresses nothing was found for are absent -- there is no row to report for them.
     */
    fun getForUser(user: User): Flow<List<EmailAvatar>>

    /** The bytes of one picture, null when no row carries [id]. */
    fun getImage(id: Uuid): Flow<ByteArray?>

    /**
     * Stores a freshly downloaded picture and returns its id. Always a new row, never an update:
     * the id is the cache key of the url the browser loads, see
     * [es.jvbabi.overmail.server.database.models.EmailAvatars].
     */
    suspend fun insert(image: ByteArray, source: String): Uuid

    /**
     * Throws away every picture [user]'s address book points at, and with it -- through
     * `ON DELETE SET NULL` -- the links to them. That is what puts those addresses back in front
     * of the resolvers.
     *
     * @return how many pictures were dropped.
     */
    suspend fun deleteForUser(user: User): Int
}

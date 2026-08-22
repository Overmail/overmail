package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.EmailUser
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface EmailUserRepository {
    fun getForUser(user: User): Flow<List<EmailUser>>
    fun getById(id: Uuid): Flow<EmailUser?>

    /** Resolves a header address for [user]; the importer takes `.first()` off it. */
    fun findByAddress(user: User, address: String): Flow<EmailUser?>

    /**
     * Inserts the address for [user] or returns the existing row, so the importer can resolve a
     * header address without racing an importer of another account of the same user.
     *
     * Takes no display name: an address keeps none, the mail it appeared in does.
     */
    suspend fun findOrCreate(user: User, address: String): EmailUser

    /**
     * Every address of [user]'s address book, once each. What the avatar resolvers work through:
     * an address is one lookup no matter how many mails it appeared in.
     */
    fun distinctAddresses(user: User): Flow<List<String>>

    /** The same, narrowed to the addresses no picture has been found for yet. */
    fun distinctAddressesWithoutAvatar(user: User): Flow<List<String>>

    /**
     * Points [user]'s entry for [address] at a stored picture.
     *
     * @return how many rows were linked, which is at most one -- the address is unique per user.
     */
    suspend fun linkAvatar(user: User, address: String, avatarId: Uuid): Int
}

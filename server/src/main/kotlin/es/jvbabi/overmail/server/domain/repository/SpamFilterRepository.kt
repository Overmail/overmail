package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.SpamFilter
import es.jvbabi.overmail.server.domain.models.User
import es.jvbabi.overmail.server.domain.spam.SpamRule
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface SpamFilterRepository {
    /** The user's filters, oldest first. Includes the ones that are switched off. */
    fun getForUser(user: User): Flow<List<SpamFilter>>

    /**
     * One filter, whoever it belongs to -- callers check the owner, as they do for mails. Emits
     * null for an id nothing is stored under.
     */
    fun getById(id: Uuid): Flow<SpamFilter?>

    /** Writes a new filter and returns it. */
    suspend fun insert(user: User, name: String, rule: SpamRule, isActive: Boolean): SpamFilter

    /**
     * Overwrites what a filter says. Returns null for an id nothing is stored under; a filter
     * never changes hands, so the owner is not part of this.
     */
    suspend fun update(id: Uuid, name: String, rule: SpamRule, isActive: Boolean): SpamFilter?
}

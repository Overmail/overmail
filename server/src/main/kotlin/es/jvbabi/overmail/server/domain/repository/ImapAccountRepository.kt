package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.ImapAccount
import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface ImapAccountRepository {
    fun getForUser(user: User): Flow<List<ImapAccount>>
    fun getById(id: Uuid): Flow<ImapAccount?>
    fun getAll(): Flow<List<ImapAccount>>
}
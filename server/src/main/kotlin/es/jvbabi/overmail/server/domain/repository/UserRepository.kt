package es.jvbabi.overmail.server.domain.repository

import es.jvbabi.overmail.server.domain.models.User
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

interface UserRepository {
    fun getById(id: Uuid): Flow<User?>
}
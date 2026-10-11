package es.jvbabi.overmail.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The address a push for this installation is sent to. Platform-specific all the way down: on
 * Android it is the Firebase Installation ID, iOS has none yet.
 */
interface PushTokenRepository {
    /**
     * The current token, and every one that replaces it. Emits nothing while there is none -- the
     * platform has no push, or the token could not be fetched.
     */
    fun getToken(): Flow<String>
}

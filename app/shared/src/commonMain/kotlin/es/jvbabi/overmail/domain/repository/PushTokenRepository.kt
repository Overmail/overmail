package es.jvbabi.overmail.domain.repository

import es.jvbabi.overmail.domain.model.PushToken
import kotlinx.coroutines.flow.Flow

/**
 * The address a push for this installation is sent to. Platform-specific all the way down: on
 * Android it is the Firebase Installation ID, on iOS the APNs token and the device's key.
 */
interface PushTokenRepository {
    /**
     * The current token, and every one that replaces it. Emits nothing while there is none -- the
     * platform has no push, or the token could not be fetched.
     */
    fun getToken(): Flow<PushToken>
}

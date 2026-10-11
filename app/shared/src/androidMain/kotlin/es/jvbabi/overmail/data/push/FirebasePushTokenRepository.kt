package es.jvbabi.overmail.data.push

import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessaging
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The registration token of Firebase Cloud Messaging: the one this installation has when the app
 * starts, and whatever [PushMessagingService] is handed after that.
 */
class FirebasePushTokenRepository : PushTokenRepository {
    private val logger = Logger.withTag("FirebasePushTokenRepository")

    private val token = MutableStateFlow<String?>(null)

    init {
        // Without Play services or a connection there is no token. The app works without one, and
        // the next start asks again.
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { onNewToken(it) }
            .addOnFailureListener { logger.w(it) { "Could not fetch the Firebase registration token" } }
    }

    override fun getToken(): Flow<String> = token.filterNotNull()

    fun onNewToken(newToken: String) {
        token.value = newToken
    }
}

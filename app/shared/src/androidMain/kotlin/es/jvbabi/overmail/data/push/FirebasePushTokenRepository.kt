package es.jvbabi.overmail.data.push

import co.touchlab.kermit.Logger
import com.google.firebase.messaging.FirebaseMessaging
import es.jvbabi.overmail.domain.model.PushToken
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * What Firebase Cloud Messaging addresses this installation by: its Firebase Installation ID.
 *
 * Firebase does not hand the id out, it reports it -- [register] makes it call
 * [PushMessagingService.onRegistered], now and again whenever the id changes. So this only asks,
 * and keeps what the service passes on.
 */
class FirebasePushTokenRepository : PushTokenRepository {
    private val logger = Logger.withTag("FirebasePushTokenRepository")

    private val installationId = MutableStateFlow<String?>(null)

    init {
        // On every start: the server is told again, which is what keeps it from holding an id
        // that went stale. Without Play services or a connection there is none, and the app
        // works without -- the next start asks again.
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener { logger.w(it) { "Could not register with Firebase Cloud Messaging" } }
    }

    override fun getToken(): Flow<PushToken> = installationId.filterNotNull().map(PushToken::Firebase)

    fun onRegistered(newInstallationId: String) {
        installationId.value = newInstallationId
    }
}

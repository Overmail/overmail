package es.jvbabi.overmail.data.push

import es.jvbabi.overmail.domain.intent.AppIntent
import es.jvbabi.overmail.domain.intent.AppIntents
import es.jvbabi.overmail.domain.model.PushMessage
import es.jvbabi.overmail.domain.model.PushToken
import es.jvbabi.overmail.domain.model.ReceivedPush
import es.jvbabi.overmail.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * What the Swift side of the app reports about push, called from `PushDelegate.swift`.
 *
 * APNs talks to the `UIApplicationDelegate`, and the device key lives in the keychain where the
 * notification service extension reads it -- both are Swift. What any of it means stays here:
 * Swift hands over what it got and is done.
 */
object ApplePush {
    private val registration = MutableStateFlow<PushToken.Apple?>(null)

    private var appIntents: AppIntents? = null
    private val pending = mutableListOf<AppIntent>()

    /** APNs gave the app [token] (hex) and the key is [publicKey] (X9.63, base64). */
    fun registered(token: String, isSandbox: Boolean, publicKey: String) {
        registration.value = PushToken.Apple(token = token, isSandbox = isSandbox, publicKey = publicKey)
    }

    /** A notification with [userInfo] was tapped: the mail it is about opens. */
    fun opened(userInfo: Map<Any?, *>) {
        val data = userInfo.entries
            .mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }
            .toMap()
        val push = ReceivedPush.fromData(data) ?: return
        val intent = when (val message = push.message) {
            // The account's id is the user's on its homeserver, see PushNotifier.
            is PushMessage.NewEmail -> AppIntent.OpenEmail(accountId = push.userId, emailId = message.emailId)
            is PushMessage.EmailsChanged, PushMessage.Ping -> return
        }

        // A tap can start the app, and arrive before Koin is up.
        appIntents?.dispatch(intent) ?: pending.add(intent)
    }

    /** Called once Koin is up: what was tapped before is handed on now. */
    internal fun attach(appIntents: AppIntents) {
        this.appIntents = appIntents
        pending.forEach(appIntents::dispatch)
        pending.clear()
    }

    internal val token: Flow<PushToken> = registration.filterNotNull()
}

class ApplePushTokenRepository : PushTokenRepository {
    override fun getToken(): Flow<PushToken> = ApplePush.token
}

package es.jvbabi.overmail.domain.intent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Where an [AppIntent] waits between the platform receiving it and the ui acting on it.
 *
 * The two are rarely ready at the same time: a tapped notification starts the activity before
 * anything is composed, so an intent is kept until somebody collects [pending] -- and handed out
 * once, so turning the device does not open the mail again.
 */
class AppIntents {
    private val channel = Channel<AppIntent>(Channel.UNLIMITED)

    /** Every intent that came in and was not acted on yet, each to one collector. */
    val pending: Flow<AppIntent> = channel.receiveAsFlow()

    fun dispatch(intent: AppIntent) {
        channel.trySend(intent)
    }

    /**
     * Takes what the platform was opened with. Whether it was an intent at all; a uri that is not
     * one is left to whoever else may want it.
     */
    fun dispatch(uri: String): Boolean {
        val intent = AppIntent.parse(uri) ?: return false
        dispatch(intent)
        return true
    }
}

package es.jvbabi.overmail.server.oauth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * The sign-ins at a provider that are under way, by the `state` they were sent off with.
 *
 * The callback comes back from the provider's page, so the state is the only thing that ties it to
 * the user who started it -- and the only thing that tells it apart from a callback somebody else
 * crafted. In memory on purpose: a sign-in takes a minute, and one that is cut off by a restart is
 * started again from the dialog.
 */
class OAuthStateStore(private val lifetime: Duration = 10.minutes) {

    /** Who started a sign-in and where. */
    data class Pending(val userId: Uuid, val provider: OAuthProvider)

    private class Entry(val pending: Pending, val expiresAt: TimeSource.Monotonic.ValueTimeMark)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** A fresh state for [userId] signing in at [provider]. */
    fun create(userId: Uuid, provider: OAuthProvider): String {
        // Swept here rather than on a timer: an abandoned sign-in is never consumed, and this is
        // the only place the map grows.
        entries.entries.removeIf { it.value.expiresAt.hasPassedNow() }

        val state = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        entries[state] = Entry(Pending(userId, provider), TimeSource.Monotonic.markNow() + lifetime)
        return state
    }

    /**
     * Who [state] was issued to, or null for an unknown or expired one. Single use: a state that
     * was answered once cannot be replayed.
     */
    fun consume(state: String): Pending? {
        val entry = entries.remove(state) ?: return null
        return entry.pending.takeUnless { entry.expiresAt.hasPassedNow() }
    }
}

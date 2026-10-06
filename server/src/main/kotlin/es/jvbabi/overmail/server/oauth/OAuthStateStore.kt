package es.jvbabi.overmail.server.oauth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The sign-ins at a provider that are under way, by the `state` they were sent off with.
 *
 * The callback comes back from the provider's page, so the state is the only thing that ties it to
 * the user who started it -- and the only thing that tells it apart from a callback somebody else
 * crafted.
 */
class OAuthStateStore(lifetime: Duration = 10.minutes) {

    /** Who started a sign-in and where. */
    data class Pending(val userId: Uuid, val provider: OAuthProvider)

    private val entries = ExpiringMap<Pending>(lifetime)

    /** A fresh state for [userId] signing in at [provider]. */
    fun create(userId: Uuid, provider: OAuthProvider): String = entries.put(Pending(userId, provider))

    /**
     * Who [state] was issued to, or null for an unknown or expired one. Single use: a state that
     * was answered once cannot be replayed.
     */
    fun consume(state: String): Pending? = entries.remove(state)
}

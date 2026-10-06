package es.jvbabi.overmail.server.oauth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Values under random, unguessable keys that are forgotten after [lifetime].
 *
 * In memory on purpose: everything kept here belongs to a sign-in that takes minutes, and one cut
 * off by a restart is started again from the dialog.
 */
internal class ExpiringMap<V : Any>(private val lifetime: Duration) {

    private class Entry<V>(val value: V, val expiresAt: TimeSource.Monotonic.ValueTimeMark)

    private val entries = ConcurrentHashMap<String, Entry<V>>()
    private val random = SecureRandom()

    /** Keeps [value] and answers the key it is kept under. */
    fun put(value: V): String {
        // Swept here rather than on a timer: an abandoned entry is never taken out, and this is
        // the only place the map grows.
        entries.entries.removeIf { it.value.expiresAt.hasPassedNow() }

        val key = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
        entries[key] = Entry(value, TimeSource.Monotonic.markNow() + lifetime)
        return key
    }

    fun get(key: String): V? = entries[key]?.takeUnless { it.expiresAt.hasPassedNow() }?.value

    /** Takes [key] out and answers what was under it, unless that had run out already. */
    fun remove(key: String): V? = entries.remove(key)?.takeUnless { it.expiresAt.hasPassedNow() }?.value
}

package es.jvbabi.overmail.domain.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One emission of something the server owns and the app caches: the [data], which always comes
 * out of the local database, and how it stands towards the server.
 */
data class CacheableResource<out T>(
    val data: T,
    val source: Source,
) {
    enum class Source {
        /** The cache, while the server is still being asked. */
        Cache,
        /** The cache with the server's answer in it. */
        Network,
        /** The cache, and it stays that: the server could not be reached or refused. */
        Fallback,
    }

    /** Whether an answer from the server is still on its way. */
    val isFetching: Boolean get() = source == Source.Cache
}

/** The data alone, for whoever does not care where it stands towards the server. */
fun <T> Flow<CacheableResource<T>>.data(): Flow<T> = map { it.data }.distinctUntilChanged()

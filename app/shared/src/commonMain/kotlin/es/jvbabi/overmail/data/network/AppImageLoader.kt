@file:OptIn(ExperimentalCoilApi::class)

package es.jvbabi.overmail.data.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import es.jvbabi.overmail.domain.model.Participant
import es.jvbabi.overmail.imageCacheDirectory
import io.ktor.client.HttpClient

/** Avatars are small; this holds thousands of them. */
private const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024

/**
 * The image loader of the whole app, as its singleton is built from. It goes through the app's
 * own client, so a picture carries the werkbank headers like every other request; the session
 * token is added per request, see [avatarRequest].
 *
 * A function rather than something only `App` sets up: a notification needs a picture too, and it
 * can be the first thing to ask for one in a process no screen was shown in.
 */
fun appImageLoader(context: PlatformContext, httpClient: HttpClient): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(
                KtorNetworkFetcherFactory(
                    httpClient = { httpClient },
                    cacheStrategy = { ServerImageCacheStrategy() },
                )
            )
        }
        .diskCache {
            DiskCache.Builder()
                .directory(imageCacheDirectory(context))
                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .build()

/**
 * The request for this correspondent's picture, or null when they have none. Kept in the loader's
 * caches by its path: a new picture is a new path, so nothing stale is shown and nothing is asked
 * for twice.
 */
fun Participant.avatarRequest(context: PlatformContext): ImageRequest.Builder? {
    val path = avatarUrl ?: return null
    val key = overmailAccount.homeserver + path
    return ImageRequest.Builder(context)
        .data(overmailAccount.homeserver.trimEnd('/') + path)
        // Behind the session like everything else on the homeserver.
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer ${overmailAccount.token}").build())
        .memoryCacheKey(key)
        .diskCacheKey(key)
}

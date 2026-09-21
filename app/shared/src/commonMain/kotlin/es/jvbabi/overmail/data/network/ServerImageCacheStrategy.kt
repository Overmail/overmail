@file:OptIn(ExperimentalCoilApi::class)

package es.jvbabi.overmail.data.network

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * What of the homeserver's pictures goes into the disk cache: a picture the server itself answered
 * with, and nothing else.
 *
 * Coil's default keeps every 2xx and a 404 as well, and serves either from then on without asking
 * again. Here that would pin two kinds of miss for good: a 404 for a picture that turns up later,
 * and -- worse, because it is a 200 -- the login or "not running" page werkbank or a proxy answers
 * with while the server is away, which then fails to decode on every start.
 *
 * Pictures never change behind their path (a new one gets a new path), so what did go in is
 * served without asking again.
 */
class ServerImageCacheStrategy : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        // Checked on the way out too, for whatever an earlier version of this let in.
        if (cacheResponse.isImageFromBackend()) CacheStrategy.ReadResult(cacheResponse)
        else CacheStrategy.ReadResult(networkRequest)

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult =
        if (networkResponse.isImageFromBackend()) CacheStrategy.WriteResult(networkResponse)
        else CacheStrategy.WriteResult.DISABLED

    private fun NetworkResponse.isImageFromBackend(): Boolean =
        code in 200 until 300 &&
            headers[BACKEND_FAMILY_HEADER] == BACKEND_FAMILY &&
            headers["Content-Type"]?.startsWith("image/") == true
}

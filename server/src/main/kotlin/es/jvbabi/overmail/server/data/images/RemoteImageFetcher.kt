package es.jvbabi.overmail.server.data.images

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The largest image that is kept; a mail's pictures are a few hundred kilobytes at most. */
internal const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

/** How many redirects an image may take before it is given up on. */
private const val MAX_REDIRECTS = 3

/** What asking for one image came to. */
sealed interface FetchedImage {
    data class Image(val contentType: String, val bytes: ByteArray) : FetchedImage

    /** An answer that will not change: not an image, too large, gone, or a host not to be asked. Kept, so it is not asked again. */
    data class Refused(val reason: String) : FetchedImage

    /** One that may come through later: a timeout, a server error. Not kept, so the next time asks again. */
    data class Failed(val reason: String) : FetchedImage
}

/**
 * Downloads the images a mail loads from the web, see `util/RemoteImages.kt`.
 *
 * The urls come out of mails anybody can send, so this is a request the server makes on a
 * stranger's behalf: nothing on an address of its own network is asked -- loopback, private,
 * link-local, the cloud's metadata service -- also not at the end of a redirect, which is why
 * redirects are followed here and not by the client. The host is looked up before the request and
 * again by the client; a name that answers differently the second time (DNS rebinding) is not
 * caught. What comes back is kept only when it says it is an image, which is all such a request
 * could hand on.
 */
class RemoteImageFetcher(
    engine: HttpClientEngine = CIO.create(),
    /** Every address [host][String] stands for; a test answers without asking DNS. */
    private val resolve: suspend (String) -> List<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
    },
) {
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
        install(UserAgent) { agent = "Overmail" }
    }

    suspend fun fetch(url: String): FetchedImage {
        var target = runCatching { Url(url) }.getOrNull() ?: return FetchedImage.Refused("not a url")
        repeat(MAX_REDIRECTS + 1) {
            if (target.protocol != URLProtocol.HTTP && target.protocol != URLProtocol.HTTPS) {
                return FetchedImage.Refused("not http")
            }
            val addresses = try {
                resolve(target.host)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                return FetchedImage.Failed("${target.host} did not resolve: ${cause.message}")
            }
            if (addresses.isEmpty() || !addresses.all(::isPublic)) return FetchedImage.Refused("${target.host} is not public")

            val outcome = try {
                client.prepareGet(target).execute { response -> read(response) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cause: Exception) {
                return FetchedImage.Failed(cause.message ?: cause::class.simpleName.orEmpty())
            }
            when (outcome) {
                is Redirect -> target = runCatching { URLBuilder(target).takeFrom(outcome.location).build() }.getOrNull()
                    ?: return FetchedImage.Refused("a redirect to no url")
                is FetchedImage -> return outcome
            }
        }
        return FetchedImage.Refused("too many redirects")
    }

    private data class Redirect(val location: String)

    private suspend fun read(response: HttpResponse): Any {
        val status = response.status
        if (status.value in 300..399) {
            val location = response.headers[HttpHeaders.Location] ?: return FetchedImage.Refused("a redirect without a location")
            return Redirect(location)
        }
        // A server that is down or busy may answer later; one that says no will say it again.
        if (status.value >= 500 || status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests) {
            return FetchedImage.Failed("answered $status")
        }
        if (status.value !in 200..299) return FetchedImage.Refused("answered $status")

        val type = response.contentType()?.withoutParameters()
        if (type == null || !type.match(ContentType.Image.Any)) return FetchedImage.Refused("not an image: $type")
        if ((response.contentLength() ?: 0) > MAX_IMAGE_BYTES) return FetchedImage.Refused("too large")

        // One byte past the limit is enough to know it is too large, without reading the rest.
        val bytes = response.bodyAsChannel().readRemaining(MAX_IMAGE_BYTES + 1).readByteArray()
        if (bytes.size > MAX_IMAGE_BYTES) return FetchedImage.Refused("too large")
        if (bytes.isEmpty()) return FetchedImage.Refused("empty")
        return FetchedImage.Image(type.toString(), bytes)
    }
}

/**
 * Whether [address] is on the internet rather than in a network of the server's own: none of
 * loopback, private ranges, link-local (the metadata service of every cloud), carrier-grade NAT,
 * unique-local IPv6, multicast or the unspecified address.
 */
internal fun isPublic(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false

    val bytes = address.address.map { it.toInt() and 0xFF }
    return when (address) {
        is Inet4Address -> when {
            bytes[0] == 0 -> false
            // Carrier-grade NAT, 100.64.0.0/10.
            bytes[0] == 100 && bytes[1] in 64..127 -> false
            // Benchmarking, 198.18.0.0/15.
            bytes[0] == 198 && bytes[1] in 18..19 -> false
            // Reserved and broadcast, 240.0.0.0/4.
            bytes[0] >= 240 -> false
            else -> true
        }
        // Unique local, fc00::/7. A mapped IPv4 address arrives as an Inet4Address already.
        is Inet6Address -> bytes[0] and 0xFE != 0xFC
        else -> false
    }
}

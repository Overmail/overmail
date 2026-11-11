package es.jvbabi.overmail.server.data.images

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Asking for an image on a stranger's behalf: only images, only so large, never the server's own network. */
class RemoteImageFetcherTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    /** Every host is a public address unless [private] names it. */
    private fun fetcher(private: Set<String> = emptySet(), engine: MockEngine) = RemoteImageFetcher(
        engine = engine,
        resolve = { host -> listOf(InetAddress.getByName(if (host in private) "10.0.0.1" else "93.184.215.14")) },
    )

    private fun pngEngine() = MockEngine { respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }

    @Test
    fun `an image is handed back with its type`() = runBlocking {
        val image = assertIs<FetchedImage.Image>(fetcher(engine = pngEngine()).fetch("https://cdn.example.com/logo.png"))

        assertEquals("image/png", image.contentType)
        assertContentEquals(png, image.bytes)
    }

    @Test
    fun `anything that is not an image is refused`() = runBlocking {
        val engine = MockEngine { respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }

        assertIs<FetchedImage.Refused>(fetcher(engine = engine).fetch("https://example.com/page"))
    }

    @Test
    fun `an image larger than the limit is refused`() = runBlocking {
        val engine = MockEngine {
            respond(ByteArray(MAX_IMAGE_BYTES.toInt() + 1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }

        assertIs<FetchedImage.Refused>(fetcher(engine = engine).fetch("https://example.com/huge.jpg"))
    }

    @Test
    fun `a host on a private address is not asked`() = runBlocking {
        var asked = false
        val engine = MockEngine { asked = true; respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }

        assertIs<FetchedImage.Refused>(fetcher(private = setOf("intranet.example.com"), engine = engine).fetch("http://intranet.example.com/x.png"))
        assertFalse(asked)
    }

    @Test
    fun `a redirect to a private address is not followed`() = runBlocking {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.host
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://intranet.example.com/x.png"))
        }

        assertIs<FetchedImage.Refused>(fetcher(private = setOf("intranet.example.com"), engine = engine).fetch("https://tracker.example.com/x"))
        assertEquals(listOf("tracker.example.com"), asked)
    }

    @Test
    fun `a redirect to a public image is followed`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/moved") respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/logo.png"))
            else respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        }

        assertIs<FetchedImage.Image>(fetcher(engine = engine).fetch("https://cdn.example.com/moved"))
    }

    @Test
    fun `a server error may come through later, a missing image will not`() = runBlocking {
        val down = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }
        val gone = MockEngine { respond("", HttpStatusCode.NotFound) }

        assertIs<FetchedImage.Failed>(fetcher(engine = down).fetch("https://example.com/a.png"))
        assertIs<FetchedImage.Refused>(fetcher(engine = gone).fetch("https://example.com/a.png"))
    }

    @Test
    fun `only addresses on the internet are public`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1")
            .forEach { assertFalse(isPublic(InetAddress.getByName(it)), it) }
        listOf("93.184.215.14", "140.82.112.3", "2606:4700::6810:84e5")
            .forEach { assertTrue(isPublic(InetAddress.getByName(it)), it) }
    }
}

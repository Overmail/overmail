package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.data.images.RemoteImageDownloader
import es.jvbabi.overmail.server.data.images.RemoteImageFetcher
import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import es.jvbabi.overmail.server.util.remoteImageUrls
import es.jvbabi.overmail.server.util.withEmbeddedImages
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import es.jvbabi.overmail.kamel.Email as KamelEmail

/** Images a mail loads from the web: found in its body, downloaded once, handed out in it. */
class RemoteImagesTest {

    private val database = testDatabase("remote-images")
    private val inserter = EmailInserterImpl(database)

    private val avatar = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 4, 5, 6)

    /** What every url was asked for how often. */
    private val asked = mutableMapOf<String, Int>()

    /** `/avatar` is an image, `/down` a server that is down, anything else not an image. */
    private val downloader = RemoteImageDownloader(
        database,
        RemoteImageFetcher(
            engine = MockEngine { request ->
                asked.merge(request.url.toString(), 1, Int::plus)
                when (request.url.encodedPath) {
                    "/avatar" -> respond(avatar, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                    "/down" -> respond("", HttpStatusCode.ServiceUnavailable)
                    else -> respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
            },
            resolve = { listOf(InetAddress.getByName("93.184.215.14")) },
        ),
    )

    private fun mail(html: String): KamelEmail = KamelEmail.parse(
        buildString {
            append("From: GitHub <noreply@github.com>\r\n")
            append("To: julius@example.com\r\n")
            append("Subject: An invitation\r\n")
            append("Date: Mon, 1 Jun 2026 12:00:00 +0000\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n\r\n")
            append(html).append("\r\n")
        }.toByteArray(),
        uid = 1,
    )

    @Test
    fun `the images of a body are the web urls of img tags and table backgrounds`() {
        val html = """
            <img src="https://avatars.example.com/avatar?s=120&amp;v=4">
            <img src='//cdn.example.com/logo.png'>
            <table><tr><td background="http://cdn.example.com/bg.jpg">x</td></tr></table>
            <img src="data:image/png;base64,AAAA"><img src="cid:logo"><img src="relative.png">
            <a href="https://example.com/page">no image</a>
            <img src="https://avatars.example.com/avatar?s=120&amp;v=4">
        """

        assertEquals(
            listOf("https://avatars.example.com/avatar?s=120&v=4", "https://cdn.example.com/logo.png", "http://cdn.example.com/bg.jpg"),
            remoteImageUrls(html),
        )
    }

    @Test
    fun `a downloaded image is written into the body, one that was not keeps its url`() = runBlocking {
        val account = database.addAccount()
        val html = """<img alt="@wi-wissen" src="https://cdn.example.com/avatar?s=120&amp;v=4"><img src="https://cdn.example.com/page"><img src="https://cdn.example.com/down">"""
        val email = assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(html), account)).email

        downloader.downloadMissing(email.id.value, html, 5.seconds)
        val shown = database.query { withEmbeddedImages(email.id.value, html) }

        assertEquals(
            """<img alt="@wi-wissen" src="data:image/png;base64,${Base64.encode(avatar)}"><img src="https://cdn.example.com/page"><img src="https://cdn.example.com/down">""",
            shown,
        )
    }

    @Test
    fun `what was answered for good is not asked again, a server that was down is`() = runBlocking {
        val account = database.addAccount()
        val html = """<img src="https://cdn.example.com/avatar"><img src="https://cdn.example.com/page"><img src="https://cdn.example.com/down">"""
        val email = assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(html), account)).email

        downloader.downloadMissing(email.id.value, html, 5.seconds)
        downloader.downloadMissing(email.id.value, html, 5.seconds)

        assertEquals(
            mapOf("https://cdn.example.com/avatar" to 1, "https://cdn.example.com/page" to 1, "https://cdn.example.com/down" to 2),
            asked,
        )
    }

    @Test
    fun `a body without web images asks for nothing`() = runBlocking {
        val account = database.addAccount()
        val html = """<p>Hi</p><img src="cid:logo">"""
        val email = assertIs<Result.Imported>(inserter.importEmailIntoDatabase(mail(html), account)).email

        downloader.downloadMissing(email.id.value, html, 5.seconds)

        assertTrue(asked.isEmpty())
        assertEquals(html, database.query { withEmbeddedImages(email.id.value, html) })
    }
}

package es.jvbabi.overmail.server.jobs.importer

import es.jvbabi.overmail.server.database.models.Attachments
import es.jvbabi.overmail.server.jobs.importer.EmailInserter.Result
import es.jvbabi.overmail.server.util.withEmbeddedImages
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import es.jvbabi.overmail.kamel.Email as KamelEmail

/** Images a mail shows in its html body: stored as such, written into the body, found in older mails. */
class EmbeddedImagesTest {

    private val database = testDatabase("embedded-images")
    private val inserter = EmailInserterImpl(database)

    private val logo = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val report = "%PDF-1.4 report".toByteArray()

    /** A newsletter: an html body with its logo in a related part, and a pdf it does not show. */
    private fun newsletter(uid: Long, html: String = """<p>Hi</p><img src="cid:logo@example.com">"""): KamelEmail = KamelEmail.parse(
        buildString {
            append("From: Ada Lovelace <ada@example.com>\r\n")
            append("To: julius@example.com\r\n")
            append("Subject: Newsletter $uid\r\n")
            append("Date: Mon, 1 Jun 2026 12:00:%02d +0000\r\n".format(uid % 60))
            append("Content-Type: multipart/mixed; boundary=outer\r\n\r\n")
            append("--outer\r\n")
            append("Content-Type: multipart/related; boundary=inner\r\n\r\n")
            append("--inner\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n\r\n")
            append(html).append("\r\n")
            append("--inner\r\n")
            append("Content-Type: image/png; name=logo.png\r\n")
            append("Content-Disposition: inline; filename=logo.png\r\n")
            append("Content-ID: <logo@example.com>\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.encode(logo)).append("\r\n")
            append("--inner--\r\n")
            append("--outer\r\n")
            append("Content-Type: application/pdf; name=report.pdf\r\n")
            append("Content-Disposition: attachment; filename=report.pdf\r\n")
            append("Content-ID: <report@example.com>\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.encode(report)).append("\r\n")
            append("--outer--\r\n")
        }.toByteArray(),
        uid = uid,
    )

    private suspend fun contentIdsByName(): Map<String, Pair<String?, Boolean?>> = database.query {
        Attachments.selectAll().associate { it[Attachments.filename] to (it[Attachments.contentId] to it[Attachments.isEmbedded]) }
    }

    @Test
    fun `an image the body shows is stored as embedded, anything else as an attachment`() = runBlocking {
        val account = database.addAccount()

        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(newsletter(1), account))

        assertEquals(
            mapOf("logo.png" to ("logo@example.com" to true), "report.pdf" to (null to false)),
            contentIdsByName(),
        )
    }

    @Test
    fun `an image the body does not reference is an attachment`() = runBlocking {
        val account = database.addAccount()

        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(newsletter(1, html = "<p>No pictures</p>"), account))

        assertEquals(null to false, contentIdsByName()["logo.png"])
    }

    @Test
    fun `the body is handed out with its images as data urls`() = runBlocking {
        val account = database.addAccount()
        val html = """<img src="cid:logo@example.com"><div style="background:url(CID:Logo%40example.com)"></div><img src="cid:missing">"""
        val email = assertIs<Result.Imported>(inserter.importEmailIntoDatabase(newsletter(1, html), account)).email

        val shown = database.query { withEmbeddedImages(email.id.value, email.htmlContent!!) }

        val dataUrl = "data:image/png;base64,${Base64.encode(logo)}"
        assertTrue(shown.startsWith("""<img src="$dataUrl"><div style="background:url($dataUrl)"></div>"""), shown)
        // Nothing by that name: left alone, a broken image like in any other client.
        assertTrue(shown.contains("""<img src="cid:missing">"""))
        assertFalse(shown.contains("report"))
    }

    @Test
    fun `the backfill finds the images of mails stored before the content id was kept`() = runBlocking {
        val account = database.addAccount()
        assertIs<Result.Imported>(inserter.importEmailIntoDatabase(newsletter(1), account))
        database.query {
            Attachments.update({ Attachments.filename eq "logo.png" }) {
                it[contentId] = null
                it[isEmbedded] = null
            }
            Attachments.update({ Attachments.filename eq "report.pdf" }) { it[isEmbedded] = null }
        }

        EmbeddedImageBackfill(database).run()

        assertEquals(
            mapOf("logo.png" to ("logo@example.com" to true), "report.pdf" to (null to false)),
            contentIdsByName(),
        )
    }
}

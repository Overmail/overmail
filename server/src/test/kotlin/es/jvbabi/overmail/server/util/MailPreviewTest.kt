package es.jvbabi.overmail.server.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The first line of a mail, as a listing shows it. */
class MailPreviewTest {

    @Test
    fun `the text part becomes one line`() {
        val preview = mailPreview(
            text = "  Hallo Julius,\n\n\tdie Rechnung liegt bei.\n\nGrüße  ",
            html = null,
        )

        // Line breaks are not the shape of a row: everything collapses into single spaces.
        assertEquals("Hallo Julius, die Rechnung liegt bei. Grüße", preview)
    }

    @Test
    fun `an html-only mail is read as text`() {
        val preview = mailPreview(
            text = null,
            html = """
                <html><head><style>.x{color:red}</style></head>
                <body><p>Ihre Bestellung ist unterwegs.</p><p>Viel Freude damit.</p></body></html>
            """.trimIndent(),
        )

        assertEquals("Ihre Bestellung ist unterwegs. Viel Freude damit.", preview)
    }

    @Test
    fun `a blank text part falls through to the html`() {
        assertEquals("Der Inhalt", mailPreview(text = "   \n ", html = "<p>Der Inhalt</p>"))
    }

    @Test
    fun `a mail with nothing readable in it has an empty preview`() {
        assertEquals("", mailPreview(text = null, html = null))
        assertEquals("", mailPreview(text = "  ", html = "<style>.x{}</style>"))
    }

    @Test
    fun `the padding a newsletter puts in front of itself is not the preview`() {
        // What a real one looks like: a combining grapheme joiner and a figure space, hundreds of
        // times, so that a mail client shows the preheader and nothing else.
        val padding = "\u034F\u2007".repeat(300)

        val preview = mailPreview(text = padding + "Kotlin Multiplatform Newsletter #17", html = null)

        assertEquals("Kotlin Multiplatform Newsletter #17", preview)
    }

    @Test
    fun `a text part that is only padding falls through to the html`() {
        val preview = mailPreview(
            text = "\u200B\u00A0\u034F ",
            html = "<p>Der eigentliche Anfang</p>",
        )

        assertEquals("Der eigentliche Anfang", preview)
    }

    @Test
    fun `soft hyphens inside words are dropped`() {
        // Newsletters hyphenate for the renderer; a one line preview is not that renderer.
        val preview = mailPreview(text = "Ihre Bankdaten, Pass\u00ADwörter, Fotos", html = null)

        assertEquals("Ihre Bankdaten, Passwörter, Fotos", preview)
    }

    @Test
    fun `a non-breaking space is a space`() {
        assertEquals("50 € im Monat", mailPreview(text = "50\u00A0€ im\u2007Monat", html = null))
    }

    @Test
    fun `text that is meant to be seen survives`() {
        // Dashes, umlauts and emoji are content, not formatting.
        assertEquals(
            "🔒 Schützen Sie Ihre Welt – jetzt",
            mailPreview(text = "🔒 Schützen Sie Ihre Welt – jetzt", html = null),
        )
    }

    @Test
    fun `a long body is cut at a word, with an ellipsis`() {
        val body = List(80) { "Wort" }.joinToString(" ")

        val preview = mailPreview(text = body, html = null)

        assertTrue(preview.endsWith("…"), preview)
        assertTrue(preview.length <= MAIL_PREVIEW_LENGTH + 1, "${preview.length}")
        // Cut between words, so the last one is whole.
        assertTrue(preview.dropLast(1).endsWith("Wort"), preview)
    }

    @Test
    fun `a line without a space in reach is cut where it is`() {
        val body = "x".repeat(400)

        val preview = mailPreview(text = body, html = null)

        assertEquals(MAIL_PREVIEW_LENGTH + 1, preview.length)
        assertEquals("x".repeat(MAIL_PREVIEW_LENGTH) + "…", preview)
    }

    @Test
    fun `a body just short of the cut keeps its ending`() {
        val body = "a".repeat(MAIL_PREVIEW_LENGTH)

        assertEquals(body, mailPreview(text = body, html = null))
    }
}

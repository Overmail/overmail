package es.jvbabi.overmail.server.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadableBodyTest {

    @Test
    fun `leaves a short mail alone`() {
        val mail = "Hallo Julius,\n\npasst dir Mittwoch?\n\nMareike"

        assertEquals(mail, readableBody(mail))
    }

    @Test
    fun `keeps a link as its host`() {
        val body = readableBody("Anmeldung: https://www.lernsax.de/wws/9.php?sid=42abc&t=1#nav")

        assertEquals("Anmeldung: lernsax.de", body)
    }

    @Test
    fun `leaves the punctuation around a link where it is`() {
        assertEquals("Siehe (bahn.de) dazu.", readableBody("Siehe (https://bahn.de/x/y) dazu."))
    }

    @Test
    fun `keeps a link that carries nothing but a host`() {
        assertEquals("github.com", readableBody("http://github.com"))
    }

    @Test
    fun `keeps both ends of a mail that runs long`() {
        val body = readableBody(
            buildString {
                appendLine("Liebe Schuelerinnen und Schueler,")
                repeat(2_000) { appendLine("Zeile $it mit etwas Text darin.") }
                appendLine("Diese Nachricht wurde ueber LernSax versendet.")
            }
        )

        // The greeting is what the mail is about, the footer is what names the platform.
        assertContains(body, "Liebe Schuelerinnen und Schueler,")
        assertContains(body, "Diese Nachricht wurde ueber LernSax versendet.")
    }

    @Test
    fun `says how much it left out`() {
        val body = readableBody("x".repeat(20_000))

        assertContains(body, "characters left out")
        assertTrue(body.length < 8_000, "cut to ${body.length} characters")
    }

    @Test
    fun `cuts on a line break rather than mid sentence`() {
        val body = readableBody((1..2_000).joinToString("\n") { "Zeile $it mit etwas Text darin." })
        val (head, tail) = body.split("\n\n[... ").let { it[0] to it[1].substringAfter("]\n\n") }

        assertTrue(head.endsWith("darin."), "head ends with: ${head.takeLast(40)}")
        assertTrue(tail.startsWith("Zeile "), "tail starts with: ${tail.take(40)}")
    }
}

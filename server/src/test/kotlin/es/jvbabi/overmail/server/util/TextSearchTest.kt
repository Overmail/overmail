package es.jvbabi.overmail.server.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextSearchTest {
    @Test
    fun `a word is found inside a longer one, whatever the case`() {
        assertTrue(TextSearch("rechnung").matches("Ihre STROMRECHNUNG"))
    }

    @Test
    fun `umlauts are the same as spelled out`() {
        assertTrue(TextSearch("gruesse").matches("Viele Grüße"))
        assertTrue(TextSearch("grüße").matches("Viele Gruesse"))
    }

    @Test
    fun `every word has to turn up, in any order and any of the texts`() {
        val search = TextSearch("oktober strom")
        assertTrue(search.matches("Stromrechnung", null, "für Oktober"))
        assertFalse(search.matches("Stromrechnung", "für November"))
    }

    @Test
    fun `a typo is forgiven from four letters on, two from eight`() {
        assertTrue(TextSearch("rechnugn").matches("Ihre Rechnung"))
        assertTrue(TextSearch("rechung").matches("Ihre Rechnung"))
        assertTrue(TextSearch("rehcnugn").matches("Ihre Rechnung"))
        assertFalse(TextSearch("rxchxuxg").matches("Ihre Rechnung"))
        assertFalse(TextSearch("mial").matches("main street"))
        assertTrue(TextSearch("mial").matches("your mail"))
    }

    @Test
    fun `a typo is matched against the beginning of a word, not anywhere in it`() {
        assertTrue(TextSearch("rechnugn").matches("Rechnungsnummer"))
        assertFalse(TextSearch("nugn").matches("Rechnung"))
    }

    @Test
    fun `scattered letters are not a match`() {
        // What fuzzyContains would let through: r, e, c, h ... in order across the whole text.
        assertFalse(TextSearch("rechnung").matches("read each channel, then go and run"))
    }

    @Test
    fun `nothing to search for lets everything through`() {
        assertTrue(TextSearch("  ").matches("anything"))
        assertTrue(TextSearch("").matches(null))
    }
}

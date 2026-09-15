package es.jvbabi.overmail.server.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgNamespaceTest {

    @Test
    fun `a bare root tag gets the namespace`() {
        val repaired = """<svg viewBox="0 0 10 10"><path d="M0 0"/></svg>""".withNamespace()

        assertEquals("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0"/></svg>""", repaired)
    }

    @Test
    fun `one that already declares it is left alone`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"/>"""

        assertEquals(svg, svg.withNamespace())
    }

    @Test
    fun `a declaration or comment in front of the tag does not hide it`() {
        val repaired = """<?xml version="1.0"?><!-- a logo --><svg viewBox="0 0 10 10"/>""".withNamespace()

        assertTrue(repaired.contains("""<svg xmlns="http://www.w3.org/2000/svg" viewBox"""), repaired)
    }

    @Test
    fun `a namespace further in does not count as the root's`() {
        val repaired = """<svg viewBox="0 0 10 10"><foreignObject xmlns="x"/></svg>""".withNamespace()

        assertTrue(repaired.startsWith("""<svg xmlns="http://www.w3.org/2000/svg" """), repaired)
    }

    @Test
    fun `anything that is not an svg is left alone`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)

        assertTrue(png.withSvgNamespace().contentEquals(png))
    }

    private fun String.withNamespace(): String = encodeToByteArray().withSvgNamespace().decodeToString()
}

package es.jvbabi.overmail.server.data.avatar

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a square needs to fit inside a circle, which is as much as anything can need. */
private val MAX_PADDING = (1 - 1 / sqrt(2.0)) / 2

class CircleFitTest {

    @Test
    fun `a logo on a flat background needs no padding`() {
        val png = png(64, 64) { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 64, 64)
            graphics.color = Color.RED
            graphics.fillOval(8, 8, 48, 48)
        }

        assertEquals(0.0, png.circlePadding())
    }

    @Test
    fun `a logo on nothing at all needs no padding`() {
        val png = png(64, 64) { graphics ->
            graphics.color = Color.BLUE
            graphics.fillOval(4, 4, 56, 56)
        }

        assertEquals(0.0, png.circlePadding())
    }

    @Test
    fun `a flat coloured square needs none either, because its corners hold nothing`() {
        val png = png(64, 64) { graphics ->
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, 64, 64)
        }

        assertEquals(0.0, png.circlePadding())
    }

    @Test
    fun `a mark drawn into the corners needs the full padding`() {
        // A mark on a coloured square, out to the very edges: the shape of a logo made for a
        // rounded rectangle, whose corners the circle would cut through.
        val png = png(64, 64) { graphics ->
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, 64, 64)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 20, 20)
            graphics.fillRect(44, 44, 20, 20)
        }

        assertEquals(MAX_PADDING, png.circlePadding()!!, absoluteTolerance = 0.005)
    }

    @Test
    fun `a mark that only just leaves the circle needs only a little padding`() {
        // A square with margins of its own: its corners sit past the inscribed circle, but only
        // just, so it has far less to give up than one drawn out to the edges.
        val png = png(128, 128) { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 128, 128)
            graphics.color = Color.BLACK
            graphics.fillRect(14, 14, 100, 100)
        }

        val padding = assertNotNull(png.circlePadding())
        assertTrue(padding in 0.02..0.09, "expected a small padding, got $padding")
    }

    @Test
    fun `one corner of content is enough to need padding`() {
        val png = png(64, 64) { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 64, 64)
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, 12, 12)
        }

        assertEquals(MAX_PADDING, png.circlePadding()!!, absoluteTolerance = 0.01)
    }

    @Test
    fun `a wide picture is judged in the square it gets centred in`() {
        // 128x96 of solid colour. Centred in a 128 square, the circle leaves the empty margins
        // above and below it outside, so the ends of the bar count as content sticking out.
        val png = png(128, 96) { graphics ->
            graphics.color = Color.GREEN
            graphics.fillRect(0, 0, 128, 96)
        }

        val padding = assertNotNull(png.circlePadding())
        assertTrue(padding > 0.05, "expected a real padding, got $padding")
    }

    @Test
    fun `anti-aliasing on the edge of a round logo is tolerated`() {
        val png = png(64, 64) { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color.ORANGE
            // Fills the whole square, so its own edge sits right on the circle being checked.
            graphics.fillOval(0, 0, 64, 64)
        }

        assertEquals(0.0, png.circlePadding())
    }

    @Test
    fun `an svg is rasterised and judged`() {
        val markInTheCorner = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                <rect x="0" y="0" width="100" height="100" fill="#123456"/>
                <rect x="0" y="0" width="30" height="30" fill="#ffffff"/>
            </svg>
        """.trimIndent().toByteArray()

        val circle = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                <circle cx="50" cy="50" r="50" fill="#123456"/>
            </svg>
        """.trimIndent().toByteArray()

        assertTrue(markInTheCorner.circlePadding()!! > 0.05)
        assertEquals(0.0, circle.circlePadding())
    }

    @Test
    fun `a jpeg keeps needing no padding despite its artefacts`() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, 64, 64)
        graphics.color = Color(0x22, 0x88, 0xCC)
        graphics.fillOval(2, 2, 60, 60)
        graphics.dispose()

        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()

        assertEquals(0.0, jpeg.circlePadding())
    }

    @Test
    fun `bytes that are not a picture cannot be judged`() {
        assertNull("not an image at all".toByteArray().circlePadding())
        assertNull(ByteArray(0).circlePadding())
    }

    @Test
    fun `an svg that names no size of its own is still judged`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><circle cx="5" cy="5" r="5"/></svg>"""

        // Batik falls back to a viewport of its own, which is enough to get an answer out of; what
        // that answer is depends on where in it the mark lands, which is not the point here.
        assertNotNull(svg.toByteArray().circlePadding())
    }

    @Test
    fun `an svg that is not well formed cannot be judged`() {
        // A file from a third party's web server must not be able to throw at the lookup.
        assertNull("""<svg xmlns="http://www.w3.org/2000/svg"><circle""".toByteArray().circlePadding())
    }

    @Test
    fun `no picture ever needs more than a square does`() {
        val png = png(64, 64) { graphics ->
            // Every corner filled and nothing in the middle: content as far out as it can get.
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, 64, 64)
            graphics.color = Color.WHITE
            graphics.fillOval(-16, -16, 96, 96)
        }

        assertTrue(png.circlePadding()!! <= MAX_PADDING)
    }

    private fun png(width: Int, height: Int, draw: (Graphics2D) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        draw(graphics)
        graphics.dispose()

        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}

package es.jvbabi.overmail.server.data.avatar

import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.ContentType
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarPngTest {

    @Test
    fun `an svg is drawn on a 512 square`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5"/></svg>"""

        val image = decode(assertNotNull(svg.toByteArray().toAvatarPng()))

        assertEquals(512, image.width)
        assertEquals(512, image.height)
    }

    @Test
    fun `a wide svg is letterboxed rather than stretched`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"><rect width="20" height="10"/></svg>"""

        val image = decode(assertNotNull(svg.toByteArray().toAvatarPng()))

        assertEquals(512, image.width)
        assertEquals(512, image.height)
        // Above the drawing is the letterbox, in the middle is the drawing.
        assertEquals(0, image.getRGB(256, 10) ushr 24)
        assertEquals(255, image.getRGB(256, 256) ushr 24)
    }

    @Test
    fun `a jpeg becomes a png of the same size`() {
        val jpeg = encode(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg")

        val png = assertNotNull(jpeg.toAvatarPng())

        assertEquals(ContentType.Image.PNG, png.imageContentType())
        val image = decode(png)
        assertEquals(40, image.width)
        assertEquals(30, image.height)
    }

    @Test
    fun `a png is kept as it is`() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(3, 3, Color.RED.rgb)
        val png = encode(image, "png")

        assertTrue(png.contentEquals(png.toAvatarPng()))
    }

    @Test
    fun `bytes that are not a picture are not converted`() {
        assertNull("not an image at all".toByteArray().toAvatarPng())
        assertNull(ByteArray(0).toAvatarPng())
        assertNull("""<svg xmlns="http://www.w3.org/2000/svg"><circle""".toByteArray().toAvatarPng())
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
}

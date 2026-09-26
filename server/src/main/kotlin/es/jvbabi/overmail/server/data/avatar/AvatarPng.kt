package es.jvbabi.overmail.server.data.avatar

import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.ContentType
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Side of the square an SVG avatar is drawn on. */
private const val SVG_SIZE = 512

/**
 * The picture as a png, which is the one format [es.jvbabi.overmail.server.database.models.EmailAvatars]
 * stores: every client can show it, and none of them has to draw a third party's SVG itself.
 *
 * A raster picture keeps its size. An SVG is drawn on a [SVG_SIZE] square: width and height are
 * both given, so Batik fits the `viewBox` into it and letterboxes a file that is not square
 * itself. A png that decodes is kept byte for byte.
 *
 * @return the png, or null when the bytes are not a picture anything here can decode.
 */
fun ByteArray.toAvatarPng(): ByteArray? {
    val type = imageContentType()

    val image = try {
        if (type == ContentType.Image.SVG) {
            rasteriseSvg(
                mapOf(
                    ImageTranscoder.KEY_WIDTH to SVG_SIZE.toFloat(),
                    ImageTranscoder.KEY_HEIGHT to SVG_SIZE.toFloat(),
                )
            )
        } else {
            ImageIO.read(ByteArrayInputStream(this))
        }
    } catch (cause: Exception) {
        null
    } catch (cause: LinkageError) {
        // An SVG that pulls a Batik code path whose optional dependency is not on the classpath.
        null
    } ?: return null

    if (type == ContentType.Image.PNG) return this

    return image.encodePng()
}

private fun BufferedImage.encodePng(): ByteArray? {
    val output = ByteArrayOutputStream()
    // False when no writer takes this image, e.g. a colour model png has no equivalent for.
    if (!ImageIO.write(this, "png", output)) return null
    return output.toByteArray()
}

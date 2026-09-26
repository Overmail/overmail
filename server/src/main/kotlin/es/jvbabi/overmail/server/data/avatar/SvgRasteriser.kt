package es.jvbabi.overmail.server.data.avatar

import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.TranscodingHints
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

/**
 * Draws the SVG with Batik, sized by [sizeHints] (`KEY_WIDTH`, `KEY_MAX_WIDTH`, …).
 *
 * Locked down on the way in, because the file came off a third party's web server: no scripts, and
 * nothing it names may be fetched.
 *
 * @throws Exception whatever Batik throws for a file it cannot draw.
 */
internal fun ByteArray.rasteriseSvg(sizeHints: Map<TranscodingHints.Key, Float>): BufferedImage? {
    val transcoder = InMemoryImageTranscoder()

    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "")
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true)
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false)
    for ((key, value) in sizeHints) transcoder.addTranscodingHint(key, value)

    transcoder.transcode(TranscoderInput(ByteArrayInputStream(this)), null)

    return transcoder.image
}

/**
 * Batik writes its result somewhere rather than returning it; the only place this one wants it is
 * memory. A named class rather than an anonymous object: the openapi compiler plugin cannot walk
 * a local class that extends a Java type.
 */
private class InMemoryImageTranscoder : ImageTranscoder() {

    var image: BufferedImage? = null

    override fun createImage(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    override fun writeImage(image: BufferedImage, output: TranscoderOutput?) {
        this.image = image
    }
}

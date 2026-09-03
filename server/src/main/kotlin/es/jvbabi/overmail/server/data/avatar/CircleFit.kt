package es.jvbabi.overmail.server.data.avatar

import es.jvbabi.overmail.server.util.imageContentType
import io.ktor.http.ContentType
import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Side of the square the picture is analysed on. A logo's corners are either filler or they are
 * not, and 256 pixels is plenty to tell the two apart while bounding what one avatar costs.
 */
private const val ANALYSIS_SIZE = 256

/**
 * How far a channel may drift and still count as the same colour.
 *
 * Generous, and JPEG is why: the ringing around a hard edge is not confined to the edge, it rides
 * along every block that touches it, so a logo drawn out to the frame leaves a fifth of the flat
 * area around it off by up to ~20. What this costs is a picture whose corners hold something in
 * almost the colour of the filler around it, which is not something anyone would see clipped.
 */
private const val CHANNEL_TOLERANCE = 24

/**
 * How much of the filler around the content may be off-colour and still count as filler. Not none
 * of it: a stray speck of compression noise is not content worth padding for, while a logo that
 * reaches into a corner is far more than two pixels in a thousand.
 */
private const val NOISE_RATIO = 0.002

/**
 * A square fits inside a circle at 1/sqrt(2) of its diameter, so this is all the padding any
 * picture can ever need: the corners are as far out as content can get.
 */
private val MAX_PADDING = (1 - 1 / sqrt(2.0)) / 2

/**
 * How much of its own box the picture has to give up on every side to fit inside a circle.
 *
 * The picture is centred in a square -- a wide banner gets its own empty margins, exactly like the
 * ui gives it -- and the colour of whatever surrounds its content is read off outside the largest
 * circle that fits in there. The answer is then how far the content reaches past that circle:
 * nothing past it means the corners hold only filler and the picture can simply be clipped, while
 * a logo drawn out to the edges has to be shrunk until the last of it comes inside.
 *
 * Anti-aliasing is why the circle is not taken literally: a rounded logo's own edge bleeds a pixel
 * or two past the inscribed circle, and that is not content anyone would miss.
 *
 * @return a fraction between `0.0` -- clip it, nothing out there is worth keeping -- and
 *   [MAX_PADDING], or null when the bytes are not an image anything here can decode.
 */
fun ByteArray.circlePadding(): Double? {
    val decoded = decodeForAnalysis() ?: return null
    return decoded.centredInSquare().paddingToFitCircle()
}

/** @return the picture as pixels, at most [ANALYSIS_SIZE] on its longer side, or null. */
private fun ByteArray.decodeForAnalysis(): BufferedImage? = try {
    if (imageContentType() == ContentType.Image.SVG) rasteriseSvg() else ImageIO.read(ByteArrayInputStream(this))
} catch (cause: Exception) {
    // Anything the readers throw is the same answer as "no reader for this": a picture we cannot
    // look at.
    null
} catch (cause: LinkageError) {
    // An SVG that pulls a Batik code path whose optional dependency is not on the classpath.
    null
}

/**
 * Draws the SVG, at most [ANALYSIS_SIZE] a side. Only the maximum is given, never a width: a width
 * makes Batik keep its default height, which distorts every logo that carries a `viewBox` and no
 * size of its own -- most of them. Left to the maximum it letterboxes such a file into a square by
 * itself, which is the square this is looking for anyway.
 *
 * Locked down on the way in, because the file came off a third party's web server: no scripts, and
 * nothing it names may be fetched.
 */
private fun ByteArray.rasteriseSvg(): BufferedImage? {
    val transcoder = InMemoryImageTranscoder()

    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "")
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true)
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false)
    transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_WIDTH, ANALYSIS_SIZE.toFloat())
    transcoder.addTranscodingHint(ImageTranscoder.KEY_MAX_HEIGHT, ANALYSIS_SIZE.toFloat())

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

/**
 * The picture on a transparent square canvas, centred, scaled down to [ANALYSIS_SIZE] if it is
 * bigger. The same square the ui puts it in, which is what makes the answer apply there.
 */
private fun BufferedImage.centredInSquare(): BufferedImage {
    val scale = minOf(1.0, ANALYSIS_SIZE.toDouble() / max(width, height))
    val scaledWidth = max(1, (width * scale).roundToInt())
    val scaledHeight = max(1, (height * scale).roundToInt())
    val side = max(scaledWidth, scaledHeight)

    val square = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    val graphics = square.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(
            this,
            (side - scaledWidth) / 2,
            (side - scaledHeight) / 2,
            scaledWidth,
            scaledHeight,
            null,
        )
    } finally {
        graphics.dispose()
    }

    return square
}

/** How far the content reaches past the inscribed circle, as padding. See [circlePadding]. */
private fun BufferedImage.paddingToFitCircle(): Double {
    val side = width
    val centre = (side - 1) / 2.0
    val inscribed = side / 2.0
    // The band anti-aliasing bleeds into. Never less than a pixel and a half, so a tiny avatar
    // does not get a tolerance of nothing.
    val band = max(1.5, side * 0.02)
    val toCorner = hypot(centre, centre)

    val filler = fillerColour(centre, inscribed + band) ?: return 0.0

    // Distance from the centre, in whole pixels: bucket n holds everything between n and n+1 away
    // from it, and the last one holds the corners.
    val pixelsAt = IntArray(ceil(toCorner).toInt() + 1)
    val offColourAt = IntArray(pixelsAt.size)

    for (y in 0 until side) {
        for (x in 0 until side) {
            val bucket = hypot(x - centre, y - centre).toInt()
            pixelsAt[bucket]++
            if (!getRGB(x, y).matches(filler)) offColourAt[bucket]++
        }
    }

    // Counted against the whole region this is about -- what lies outside the inscribed circle --
    // rather than against however far the scan below has got, so that the handful of pixels in the
    // outermost bucket do not each hold a veto.
    val budget = (inscribed.toInt() until pixelsAt.size).sumOf { pixelsAt[it] } * NOISE_RATIO

    // Inward from the corners, for as long as what is left out there is still filler. Where that
    // stops is where the content begins.
    var offColour = 0
    var contentRadius = toCorner
    for (bucket in pixelsAt.indices.reversed()) {
        offColour += offColourAt[bucket]
        if (offColour > budget) break
        contentRadius = bucket.toDouble()
    }

    if (contentRadius <= inscribed + band) return 0.0

    // +1, because the bucket is only a lower bound on how far the outermost content actually sits.
    return ((1 - inscribed / (contentRadius + 1)) / 2).coerceIn(0.0, MAX_PADDING)
}

/**
 * The colour of whatever surrounds the content: the commonest one further than [radius] from
 * [centre], rather than the pixel in the very corner, because that one is as likely to be a
 * resampling artefact as anything else.
 *
 * @return null when there is nothing out there at all, which is a picture too small for the circle
 *   to leave anything over.
 */
private fun BufferedImage.fillerColour(centre: Double, radius: Double): Int? {
    val cutoff = radius * radius
    val outside = ArrayList<Int>()

    for (y in 0 until height) {
        for (x in 0 until width) {
            val dx = x - centre
            val dy = y - centre
            if (dx * dx + dy * dy <= cutoff) continue
            outside.add(getRGB(x, y))
        }
    }

    if (outside.isEmpty()) return null

    val commonest = outside.groupingBy { it.quantised() }.eachCount().maxBy { (_, count) -> count }.key
    return outside.first { pixel -> pixel.quantised() == commonest }
}

/**
 * The pixel with its channels premultiplied and cut to five bits each, so counting how often a
 * colour occurs is not defeated by the last bit of a gradient.
 */
private fun Int.quantised(): Int {
    val alpha = alpha()
    var bucket = alpha shr 3
    for (shift in 16 downTo 0 step 8) {
        bucket = (bucket shl 5) or ((channel(shift) * alpha / 255) shr 3)
    }
    return bucket
}

/**
 * Whether two pixels are the same colour within [CHANNEL_TOLERANCE]. Compared premultiplied,
 * because the rgb of a fully transparent pixel is arbitrary and must not count for anything.
 */
private fun Int.matches(other: Int): Boolean {
    val alpha = alpha()
    val otherAlpha = other.alpha()
    if (abs(alpha - otherAlpha) > CHANNEL_TOLERANCE) return false

    return (16 downTo 0 step 8).all { shift ->
        abs(channel(shift) * alpha / 255 - other.channel(shift) * otherAlpha / 255) <= CHANNEL_TOLERANCE
    }
}

private fun Int.alpha(): Int = this ushr 24 and 0xFF

private fun Int.channel(shift: Int): Int = this ushr shift and 0xFF

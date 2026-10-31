package es.jvbabi.overmail.ui.components.blitz

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import co.touchlab.kermit.Logger
import es.jvbabi.overmail.openUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Html mails are rendered by Blitz (`app/html-renderer`) rather than a web view. It lays a mail
 * out and rasterises it in the process, into bitmaps Compose draws like any other image -- so a
 * mail turns, scales, fades and is blurred with the card or the page it is on, as nothing else
 * does: a web view either lies over the Compose layer (iOS) or crashes and flickers in the
 * offscreen layers of a card (Android).
 *
 * Two sizes, decided apart:
 * - The layout is the device's, not the view's: as wide as the window in dp, but never narrower
 *   than [EMAIL_DESIGN_WIDTH], which is what mails are made for. A phone lays a mail out at that
 *   and shows it zoomed out, a tablet at its natural size. A card and the page that grows out of
 *   it -- or shrinks back into it on a predictive back -- share it, so nothing ever reflows between
 *   them: a card is the page, made smaller.
 * - The raster is the view's: exactly as many px as it is wide on the screen, drawn 1:1, so
 *   nothing is resampled and blurred. A view that has no tiles at its own raster yet draws
 *   those of another one of the same layout in the meantime -- the page grows out of the card's,
 *   and only gets sharper when its own come in.
 */

private val logger = Logger.withTag("BlitzMailView")

/** The width html mails are typically designed for, in css px; none is laid out narrower. */
private const val EMAIL_DESIGN_WIDTH = 640

/** How tall the viewport is that a mail measures `vh` and a screen-sized layout against, in widths of it. */
private const val VIEWPORT_ASPECT = 1.6f

/** How tall a tile is, in px of its raster. */
private const val TILE = 1024

/** How many layouts stay open: a few per mail of the pile that shows, and a page. */
private const val KEPT_MAILS = 12

/** How many bytes of tiles stay rendered. */
private const val KEPT_TILE_BYTES = 128L shl 20

/**
 * The native side: `app/html-renderer`, through JNI on Android and its C functions on iOS. Every
 * call blocks until the renderer's own thread has done it, so none is made on the main thread.
 */
internal expect object BlitzNative {
    /** [html] laid out; a handle, or 0 when it would not be. */
    fun open(html: String, cssWidth: Int, viewportHeight: Int, scale: Float): Long

    /** How tall the mail of [handle] is, in px of the render. */
    fun height(handle: Long): Int

    /** The rows [top] to [top] + [height] as RGBA, `cssWidth * scale` px a row; null when they would not render. */
    fun render(handle: Long, top: Int, height: Int): ByteArray?

    /** The `href` of the link at [x], [y] in px of the render, or null. */
    fun linkAt(handle: Long, x: Float, y: Float): String?

    fun close(handle: Long)
}

/** [rgba], [width] × [height] px of opaque RGBA, as a bitmap to draw. */
internal expect fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap

/** A mail laid out [layoutWidth] css px wide, to be rasterised [rasterWidth] px wide. */
internal data class BlitzKey(val document: String, val layoutWidth: Int, val rasterWidth: Int)

/** A mail laid out by Blitz, [width] × [height] px of raster. */
internal class BlitzMail(val key: BlitzKey, val handle: Long, val width: Int, val height: Int) {
    val tiles: Int get() = ceil(height.toFloat() / TILE).toInt()
}

/** What a view draws of one raster: its mail and the tiles of it it has. */
private class Raster(val mail: BlitzMail, val tiles: Map<Int, ImageBitmap>)

/**
 * The mails laid out and the tiles rendered, shared by every view that shows them -- what a card
 * rendered, the page that grows out of it draws from its first frame. The least recently used go
 * first. Touched on the main thread only; the native calls are made off it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object BlitzMails {
    private val mails = LinkedHashMap<BlitzKey, Deferred<BlitzMail?>>()
    private val tiles = LinkedHashMap<Pair<BlitzKey, Int>, ImageBitmap>()
    private var tileBytes = 0L

    /** The mail of [key] if it is laid out already, so a view can draw it in its first frame. */
    fun peek(key: BlitzKey): BlitzMail? = mails[key]?.takeIf { it.isCompleted }?.getCompleted()

    fun peekTile(key: BlitzKey, index: Int): ImageBitmap? = tiles[key to index]

    /** Every other raster of the same layout that has tiles, sharpest first: what to draw until [key]'s own are in. */
    fun standIns(key: BlitzKey): List<BlitzMail> = mails.keys
        .filter { it != key && it.document == key.document && it.layoutWidth == key.layoutWidth }
        .mapNotNull { peek(it) }
        .filter { mail -> (0 until mail.tiles).any { tiles[mail.key to it] != null } }
        .sortedByDescending { it.width }

    suspend fun open(key: BlitzKey): BlitzMail? {
        mails.remove(key)?.let { mails[key] = it; return it.await() }
        val opening = CompletableDeferred<BlitzMail?>()
        mails[key] = opening
        while (mails.size > KEPT_MAILS) forget(mails.keys.first())
        val scale = key.rasterWidth.toFloat() / key.layoutWidth
        val mail = withContext(Dispatchers.Default) {
            val viewportHeight = (key.layoutWidth * VIEWPORT_ASPECT).roundToInt()
            val handle = BlitzNative.open(key.document, key.layoutWidth, viewportHeight, scale)
            if (handle == 0L) null else BlitzMail(key, handle, key.rasterWidth, BlitzNative.height(handle))
        }
        if (mail == null) logger.w { "A mail would not lay out" }
        opening.complete(mail)
        return mail
    }

    suspend fun tile(mail: BlitzMail, index: Int): ImageBitmap? {
        val cacheKey = mail.key to index
        tiles.remove(cacheKey)?.let { tiles[cacheKey] = it; return it }
        val top = index * TILE
        val height = minOf(TILE, mail.height - top)
        if (height <= 0) return null
        // Kept even when whoever asked has moved on: the next to ask finds it rendered.
        val bitmap = withContext(Dispatchers.Default + NonCancellable) {
            BlitzNative.render(mail.handle, top, height)?.let { rgbaToImageBitmap(it, mail.width, height) }
        } ?: return null
        // Let go of in the meantime: it is rendered, but not kept for a mail nobody holds.
        if (peek(mail.key) !== mail) return bitmap
        tiles[cacheKey] = bitmap
        tileBytes += bitmap.bytes
        while (tileBytes > KEPT_TILE_BYTES) tiles.remove(tiles.keys.first())?.let { tileBytes -= it.bytes }
        return bitmap
    }

    suspend fun linkAt(mail: BlitzMail, x: Float, y: Float): String? =
        withContext(Dispatchers.Default) { BlitzNative.linkAt(mail.handle, x, y) }

    private val ImageBitmap.bytes: Long get() = width.toLong() * height * 4

    private fun forget(key: BlitzKey) {
        val mail = mails.remove(key) ?: return
        tiles.keys.filter { it.first == key }.forEach { tiles.remove(it)?.let { tile -> tileBytes -= tile.bytes } }
        // Closed once it is laid out, which one still opening is about to be.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            mail.await()?.let { withContext(Dispatchers.Default) { BlitzNative.close(it.handle) } }
        }
    }
}

/**
 * [document] rendered by Blitz, as wide as it is given; it tells [onContentHeight] how tall the
 * mail is at that width, and is to be made that tall. Only the tiles near the window are rendered.
 * With [links], a tap on a link opens it.
 */
@Composable
internal fun BlitzMailView(document: String, onContentHeight: (Dp) -> Unit, links: Boolean, modifier: Modifier = Modifier) {
    // The width while composing, not once laid out: the first frame already draws what there is.
    BoxWithConstraints(modifier) {
        BlitzMailContent(document, onContentHeight, links, if (constraints.hasBoundedWidth) constraints.maxWidth else 0)
    }
}

@Composable
private fun BlitzMailContent(document: String, onContentHeight: (Dp) -> Unit, links: Boolean, width: Int) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val currentOnContentHeight by rememberUpdatedState(onContentHeight)

    val layoutWidth = with(density) { window.width.toDp().value }.roundToInt().coerceAtLeast(EMAIL_DESIGN_WIDTH)
    val key = remember(document, layoutWidth, width) {
        if (width == 0) null else BlitzKey(document, layoutWidth, width)
    }

    var mail by remember(key) { mutableStateOf(key?.let(BlitzMails::peek)) }
    // What is rendered already is there from the first frame.
    val tiles = remember(key) {
        mutableStateMapOf<Int, ImageBitmap>().apply {
            mail?.let { mail -> repeat(mail.tiles) { index -> BlitzMails.peekTile(mail.key, index)?.let { put(index, it) } } }
        }
    }
    // Another raster of the same layout, drawn until this one's tiles are in.
    val standIn = remember(key) {
        key?.let(BlitzMails::standIns)?.firstOrNull()?.let { other ->
            Raster(other, (0 until other.tiles).mapNotNull { index -> BlitzMails.peekTile(other.key, index)?.let { index to it } }.toMap())
        }
    }
    // Where the view is in the window, and from that and the mail which tiles it wants: worked out
    // again whenever either changes, so a mail laid out after the view last moved finds its tiles.
    var viewTop by remember { mutableStateOf<Float?>(null) }
    val windowHeight by rememberUpdatedState(window.height)
    val wanted by remember(key) {
        derivedStateOf {
            val mail = mail ?: return@derivedStateOf IntRange.EMPTY
            val top = viewTop ?: return@derivedStateOf IntRange.EMPTY
            // A screen above and below what shows, so a scroll finds its tiles rendered.
            val tileHeight = TILE * width.toFloat() / mail.width
            val from = floor((-top - windowHeight) / tileHeight).toInt().coerceAtLeast(0)
            val to = floor((-top + 2 * windowHeight) / tileHeight).toInt().coerceAtMost(mail.tiles - 1)
            from..to
        }
    }
    // Tiles on their way, each in a coroutine of its own: a scroll that moves what is wanted
    // must not cancel a tile halfway, or a view scrolled steadily would never get one.
    val loading = remember(key) { mutableSetOf<Int>() }

    LaunchedEffect(key) { if (key != null && mail == null) mail = BlitzMails.open(key) }

    // The height is the layout's, so a stand-in tells it as well as the mail itself.
    val measured = mail ?: standIn?.mail
    LaunchedEffect(measured?.height, measured?.width, width) {
        val known = measured ?: return@LaunchedEffect
        if (width > 0) currentOnContentHeight(with(density) { (known.height * width.toFloat() / known.width).toDp() })
    }

    LaunchedEffect(mail, wanted) {
        val mail = mail ?: return@LaunchedEffect
        val range = wanted
        tiles.keys.retainAll { it in range }
        // What shows first, then outwards.
        val center = (range.first + range.last) / 2f
        for (index in range.sortedBy { kotlin.math.abs(it - center) }) {
            if (index in tiles || !loading.add(index)) continue
            scope.launch {
                try {
                    BlitzMails.tile(mail, index)?.let { if (index in wanted) tiles[index] = it }
                } finally {
                    loading.remove(index)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewTop = it.positionInWindow().y }
            .then(
                if (!links) Modifier
                else Modifier.pointerInput(mail) {
                    val tapped = mail ?: return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Only taps: a finger that moves is a scroll, and left to it.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                        val ratio = tapped.width / size.width.toFloat()
                        scope.launch {
                            val href = BlitzMails.linkAt(tapped, up.position.x * ratio, up.position.y * ratio) ?: return@launch
                            if (href.startsWith("http://") || href.startsWith("https://")) openUrl(href)
                            else runCatching { uriHandler.openUri(href) }.onFailure { logger.w { "Nothing opens $href" } }
                        }
                    }
                },
            )
            .drawBehind {
                standIn?.let { drawRaster(it) }
                mail?.let { drawRaster(Raster(it, tiles)) }
            },
    )
}

/** The tiles of [raster], scaled from its width to this one's; the tiles are opaque, so what is drawn later covers. */
private fun DrawScope.drawRaster(raster: Raster) {
    val ratio = size.width / raster.mail.width
    for ((index, tile) in raster.tiles) {
        // Each edge rounded on its own, so neighbouring tiles meet without a seam.
        val top = (index * TILE * ratio).roundToInt()
        val bottom = ((index * TILE + tile.height) * ratio).roundToInt()
        drawImage(
            image = tile,
            dstOffset = IntOffset(0, top),
            dstSize = IntSize(size.width.roundToInt(), bottom - top),
            // A stand-in from a wider raster is scaled down, which plain bilinear filtering aliases.
            filterQuality = FilterQuality.Medium,
        )
    }
}

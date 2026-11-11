package es.jvbabi.overmail.ui.components.blitz

import co.touchlab.kermit.Logger
import coil3.disk.DiskCache
import okio.Buffer
import okio.Deflater
import okio.FileSystem
import okio.Inflater
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.deflate
import okio.inflate
import okio.use

private val logger = Logger.withTag("BlitzDiskCache")

/**
 * Bumped whenever the renderer draws differently -- a new Blitz, a rule in `FIXES` -- so every
 * mail is rendered again once. What an older generation left is deleted.
 */
internal const val BLITZ_CACHE_GENERATION = 3

/** How much the rendered mails may take on disk; the least recently used go first. */
private const val MAX_SIZE_BYTES = 256L shl 20

/**
 * What Blitz made of a mail, kept across starts: its height and its tiles, under a hash of every
 * input they depend on. A mail rendered before is drawn from here without Blitz laying it out at
 * all -- which is what has the cards of a cold start there at once, on iOS in particular.
 *
 * Tiles are stored as their raw RGBA, deflated at the fastest level: a mail is mostly one colour,
 * so that shrinks a lot, and reading it back costs no image decoder.
 *
 * Every call blocks on the disk, so none is made on the main thread. Best effort throughout: what
 * cannot be read or written is a miss, and the mail is rendered as if it had never been.
 */
internal class BlitzDiskCache(
    private val root: Path,
    generation: Int = BLITZ_CACHE_GENERATION,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val maxSizeBytes: Long = MAX_SIZE_BYTES,
) {
    private val directory = root / "v$generation"

    private val cache: DiskCache by lazy {
        dropOtherGenerations()
        DiskCache.Builder()
            .fileSystem(fileSystem)
            .directory(directory)
            .maxSizeBytes(maxSizeBytes)
            .build()
    }

    /** How tall the mail of [id] is, in px of its raster, or null when it was never laid out. */
    fun height(id: String): Int? = read(id) { readUtf8().toIntOrNull() }

    fun putHeight(id: String, height: Int) = write(id) { writeUtf8(height.toString()) }

    /** The RGBA of tile [index] of the mail of [id], if it is stored and [byteCount] bytes long. */
    fun tile(id: String, index: Int, byteCount: Int): ByteArray? = read(tileKey(id, index), inflate = true) {
        readByteArray().takeIf { it.size == byteCount }
    }

    fun putTile(id: String, index: Int, rgba: ByteArray) = write(tileKey(id, index), deflate = true) { write(rgba) }

    private fun tileKey(id: String, index: Int) = "$id-$index"

    private fun <T : Any> read(key: String, inflate: Boolean = false, block: okio.BufferedSource.() -> T?): T? = try {
        cache.openSnapshot(key)?.use { snapshot ->
            val source = fileSystem.source(snapshot.data)
            (if (inflate) source.inflate(Inflater(true)) else source).buffer().use { it.block() }
        }
    } catch (e: Exception) {
        logger.w(e) { "Could not read $key" }
        null
    }

    private fun write(key: String, deflate: Boolean = false, block: okio.BufferedSink.() -> Unit) {
        try {
            // Somebody else writing it already: theirs will do.
            val editor = cache.openEditor(key) ?: return
            try {
                val sink = fileSystem.sink(editor.data)
                (if (deflate) sink.deflate(Deflater(1, true)) else sink).buffer().use { it.block() }
                editor.commit()
            } catch (e: Exception) {
                editor.abort()
                throw e
            }
        } catch (e: Exception) {
            logger.w(e) { "Could not write $key" }
        }
    }

    private fun dropOtherGenerations() {
        try {
            fileSystem.listOrNull(root)?.filter { it != directory }?.forEach { fileSystem.deleteRecursively(it) }
        } catch (e: Exception) {
            logger.w(e) { "Could not delete what an older renderer cached" }
        }
    }

    companion object {
        /** The key of a mail: [document] laid out [layoutWidth] css px wide, rasterised [rasterWidth] px wide, in tiles [tileHeight] px tall. */
        fun id(document: String, layoutWidth: Int, rasterWidth: Int, viewportHeight: Int, tileHeight: Int): String =
            Buffer()
                .writeUtf8("$layoutWidth $rasterWidth $viewportHeight $tileHeight\n")
                .writeUtf8(document)
                .sha256()
                .hex()
    }
}

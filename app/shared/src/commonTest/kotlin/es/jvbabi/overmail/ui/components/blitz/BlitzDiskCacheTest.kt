package es.jvbabi.overmail.ui.components.blitz

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlitzDiskCacheTest {
    private val fileSystem = FileSystem.SYSTEM
    private val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "blitz-disk-cache-test-${Random.nextLong().toULong()}".toPath()

    @AfterTest
    fun cleanUp() = fileSystem.deleteRecursively(root)

    private fun id(document: String = "<p>Hi</p>", rasterWidth: Int = 1080) =
        BlitzDiskCache.id(document, layoutWidth = 640, rasterWidth = rasterWidth, viewportHeight = 1024, tileHeight = 1024)

    @Test
    fun `a stored height and tile are read back`() {
        val cache = BlitzDiskCache(root, generation = 1)
        val rgba = ByteArray(1080 * 20 * 4) { (it % 7).toByte() }
        cache.putHeight(id(), 2400)
        cache.putTile(id(), 2, rgba)

        val reopened = BlitzDiskCache(root, generation = 1)
        assertEquals(2400, reopened.height(id()))
        assertContentEquals(rgba, reopened.tile(id(), 2, rgba.size))
    }

    @Test
    fun `what was never stored is a miss`() {
        val cache = BlitzDiskCache(root, generation = 1)
        assertNull(cache.height(id()))
        assertNull(cache.tile(id(), 0, 16))
    }

    @Test
    fun `a tile of another size is a miss`() {
        val cache = BlitzDiskCache(root, generation = 1)
        cache.putTile(id(), 0, ByteArray(16))
        assertNull(cache.tile(id(), 0, 32))
    }

    @Test
    fun `every input is part of the key`() {
        assertNotEquals(id(), id(document = "<p>Ho</p>"))
        assertNotEquals(id(), id(rasterWidth = 720))
        assertEquals(id(), id())
    }

    @Test
    fun `another generation starts empty and deletes the old one`() {
        BlitzDiskCache(root, generation = 1).putHeight(id(), 2400)
        assertTrue(fileSystem.exists(root / "v1"))

        val next = BlitzDiskCache(root, generation = 2)
        assertNull(next.height(id()))
        assertTrue(!fileSystem.exists(root / "v1"))
    }
}

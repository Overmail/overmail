package es.jvbabi.overmail.ui.components.blitz

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

internal actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))
    bitmap.installPixels(rgba)
    // Never changes again, so Skia may keep it on the GPU as it is.
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

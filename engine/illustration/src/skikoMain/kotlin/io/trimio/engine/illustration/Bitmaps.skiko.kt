package io.trimio.engine.illustration

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual fun Raster.toImageBitmap(): ImageBitmap {
    // BGRA8888 in memory is 0xAARRGGBB read as a little-endian int.
    val bytes = ByteArray(width * height * BYTES)
    for (i in argb.indices) {
        val c = argb[i]
        bytes[i * BYTES] = (c and MASK).toByte()
        bytes[i * BYTES + 1] = (c shr 8 and MASK).toByte()
        bytes[i * BYTES + 2] = (c shr 16 and MASK).toByte()
        bytes[i * BYTES + 3] = (c ushr 24).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    bitmap.installPixels(bytes)
    return bitmap.asComposeImageBitmap()
}

private const val BYTES = 4
private const val MASK = 0xFF

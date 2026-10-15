package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun alphaImage(alpha: FloatArray, size: Int): ImageBitmap {
    // Premultiplied RGBA: white at the matte's alpha.
    val bytes = ByteArray(size * size * 4)
    for (i in 0 until size * size) {
        val a = (alpha[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        bytes[i * 4] = a
        bytes[i * 4 + 1] = a
        bytes[i * 4 + 2] = a
        bytes[i * 4 + 3] = a
    }
    return Image.makeRaster(ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, size * 4).toComposeImageBitmap()
}

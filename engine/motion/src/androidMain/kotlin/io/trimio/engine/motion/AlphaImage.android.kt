package io.trimio.engine.motion

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun alphaImage(alpha: FloatArray, size: Int): ImageBitmap {
    val colors = IntArray(size * size) { i ->
        val a = (alpha[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (a shl 24) or 0x00FFFFFF
    }
    return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

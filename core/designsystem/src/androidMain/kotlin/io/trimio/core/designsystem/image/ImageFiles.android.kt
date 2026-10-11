package io.trimio.core.designsystem.image

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual fun ImageBitmap.encodeJpeg(quality: Int): ByteArray? = runCatching {
    ByteArrayOutputStream().use { out ->
        asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}.getOrNull()

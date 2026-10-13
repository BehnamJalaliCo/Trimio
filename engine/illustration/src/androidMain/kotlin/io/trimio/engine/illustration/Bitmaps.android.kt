package io.trimio.engine.illustration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun Raster.toImageBitmap(): ImageBitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

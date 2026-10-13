package io.trimio.engine.illustration

import androidx.compose.ui.graphics.ImageBitmap

/** The picture as a Compose bitmap for the stage (Android: a platform Bitmap; elsewhere: Skia). */
expect fun Raster.toImageBitmap(): ImageBitmap

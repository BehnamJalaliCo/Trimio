package io.trimio.core.designsystem.image

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Encodes a frame as JPEG (posters, thumbnails); null when the platform cannot encode it. */
expect fun ImageBitmap.encodeJpeg(quality: Int = 88): ByteArray?

/** Decodes a PNG or JPEG; null for bytes that are not an image. */
@OptIn(ExperimentalResourceApi::class)
fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching { bytes.decodeToImageBitmap() }.getOrNull()

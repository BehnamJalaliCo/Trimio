package io.trimio.engine.render

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** Android loads fonts from files; the bytes are written once into the app's cache directory. */
internal actual fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight): Font {
    val file = File(System.getProperty("java.io.tmpdir"), "$identity.ttf")
    if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
    return Font(file, weight, FontStyle.Normal, FontVariation.Settings(weight, FontStyle.Normal))
}

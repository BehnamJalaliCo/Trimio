package io.trimio.engine.motion

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** Android loads fonts from files; each face is written once into the temp directory. */
internal actual fun platformFont(identity: String, bytes: ByteArray, weight: FontWeight, variable: Boolean): Font {
    val file = File(System.getProperty("java.io.tmpdir"), "m-$identity.bin")
    if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
    return Font(file, weight, FontStyle.Normal, if (variable) FontVariation.Settings(weight, FontStyle.Normal) else FontVariation.Settings())
}

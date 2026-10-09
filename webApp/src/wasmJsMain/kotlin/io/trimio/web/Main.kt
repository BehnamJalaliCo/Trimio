package io.trimio.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.model.input.MediaUri
import io.trimio.shared.TrimioApp
import io.trimio.shared.di.previewPlatformModule
import io.trimio.shared.initTrimio
import io.trimio.shared.uiLanguageFor
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import kotlin.coroutines.resume

/** Picks a file with the browser's own dialog; the file stays in the browser (an object URL). */
private object BrowserMediaPicker : MediaPicker {
    override suspend fun pick(kind: MediaKind): MediaUri? = suspendCancellableCoroutine { cont ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = if (kind == MediaKind.Video) "video/*" else "audio/*"
        input.onchange = {
            val file = input.files?.item(0)
            if (cont.isActive) cont.resume(file?.let { MediaUri(URL.createObjectURL(it)) })
        }
        input.addEventListener("cancel", { if (cont.isActive) cont.resume(null) })
        input.click()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initTrimio(previewPlatformModule("Web", BrowserMediaPicker))
    document.getElementById("splash")?.remove()
    ComposeViewport(document.body!!) { TrimioApp(uiLanguageFor(window.navigator.language)) }
}

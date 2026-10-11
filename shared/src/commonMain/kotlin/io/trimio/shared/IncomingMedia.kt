package io.trimio.shared

import io.trimio.core.data.MediaKind
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Media that arrives from outside the app's own picker: a file dropped on the desktop window, a
 * video opened with Trimio from the file manager (desktop file associations), later Android's
 * share sheet. The app's navigation opens the create screen for each one. Optional: platforms
 * that register none simply never receive any.
 */
class IncomingMedia {
    data class Item(val uri: String, val kind: MediaKind)

    // Buffered: a file opened at launch arrives before the navigation starts collecting.
    private val items = Channel<Item>(Channel.BUFFERED)

    val requests: Flow<Item> = items.receiveAsFlow()

    fun open(uri: String, kind: MediaKind) {
        items.trySend(Item(uri, kind))
    }
}

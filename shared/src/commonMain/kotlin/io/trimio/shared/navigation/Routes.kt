package io.trimio.shared.navigation

import io.trimio.core.data.MediaKind
import io.trimio.engine.production.ProductionRequest

/** Every destination; the back stack is a plain list the app owns (Navigation 3). */
sealed interface Route {
    data object Onboarding : Route
    data object Studio : Route
    data object Gallery : Route
    data object Settings : Route
    data class Create(val uri: String, val kind: MediaKind, val styleId: String? = null) : Route
    data class Build(val projectId: String) : Route
    data class Editor(val projectId: String) : Route
    data class Export(val projectId: String) : Route

    /** The automatic engine at work on [request] (live build view, then its failure or result). */
    data class Produce(val request: ProductionRequest, val styleName: String? = null, val version: Int = 1) : Route

    /** The finished edit of the active production; [version] counts variations and revisions. */
    data class Result(val buildSeconds: Int, val version: Int = 1, val styleName: String? = null) : Route

    data object Models : Route
}

package io.trimio.shared.navigation

import io.trimio.core.data.MediaKind
import io.trimio.engine.production.ProductionRequest

/** Every destination; the back stack is a plain list the app owns (Navigation 3). */
sealed interface Route {
    data object Onboarding : Route
    data object Studio : Route
    data object Gallery : Route
    data object Settings : Route
    /** The brief for [uri]; [prompt] starts it with an idea's or the composer's words. */
    data class Create(val uri: String, val kind: MediaKind, val styleId: String? = null, val prompt: String? = null) : Route
    data class Editor(val projectId: String) : Route
    data class Export(val projectId: String) : Route

    /**
     * The automatic engine at work on [request] for project [projectId] (live build view, then its
     * failure or result). [retouch]: the editor's changes being rendered as the next version.
     */
    data class Produce(
        val request: ProductionRequest,
        val styleName: String? = null,
        val version: Int = 1,
        val projectId: String? = null,
        val retouch: Boolean = false,
    ) : Route

    /**
     * The finished edit of project [projectId]: the run that just finished, or the saved one when
     * opened from home. [version] counts variations and revisions.
     */
    data class Result(val buildSeconds: Int, val version: Int = 1, val styleName: String? = null, val projectId: String? = null) : Route

    data object Models : Route
}

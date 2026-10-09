package io.trimio.shared.navigation

import io.trimio.core.data.MediaKind

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
}

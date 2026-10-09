package io.trimio.shared

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.trimio.core.api.RemoteRepository
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.Entitlements
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.theme.TrimioMotionScheme
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelManager
import io.trimio.feature.create.CreateRoute
import io.trimio.feature.editor.EditorRoute
import io.trimio.feature.editor.ExportRoute
import io.trimio.feature.gallery.StyleGalleryRoute
import io.trimio.feature.settings.SettingsRoute
import io.trimio.feature.stream.BuildStreamRoute
import io.trimio.feature.studio.OnboardingScreen
import io.trimio.feature.studio.StudioRoute
import io.trimio.shared.di.AppScope
import io.trimio.shared.di.CreateArgs
import io.trimio.shared.di.appModule
import io.trimio.shared.navigation.Route
import io.trimio.shared.ui.UpdateRequiredScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf

/** Starts dependency injection once per process, before any UI or service touches it. */
fun initTrimio(platform: Module) {
    startKoin { modules(appModule, platform) }
}

/**
 * Root of the app on every platform. [systemLanguage] is the OS language; the user can override it
 * in settings. Requires [initTrimio] to have run.
 */
@Composable
fun TrimioApp(systemLanguage: Language, systemPreferences: TrimioPreferences = TrimioPreferences()) {
    val settingsRepo = koinInject<SettingsRepository>()
    val projects = koinInject<ProjectRepository>()
    val remote = koinInject<RemoteRepository>()
    val entitlements = koinInject<Entitlements>()
    val appScope = koinInject<CoroutineScope>(AppScope)
    val device = koinInject<DeviceInfo>()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settingsRepo.load()
        projects.load()
        ready = true
        // Config, catalogue and pack updates arrive in the background; the app never waits for them.
        appScope.launch {
            remote.refresh()
            entitlements.billing.refresh()
        }
    }
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    val remoteState by remote.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val preferences = TrimioPreferences(
        reduceMotion = systemPreferences.reduceMotion || settings.reduceMotion,
        reduceTransparency = systemPreferences.reduceTransparency || settings.reduceTransparency,
        haptics = settings.haptics,
    )
    TrimioTheme(language = settings.language ?: systemLanguage, preferences = preferences) {
        if (!ready) {
            Box(Modifier.fillMaxSize().background(io.trimio.core.designsystem.theme.Trimio.colors.canvas))
            return@TrimioTheme
        }
        if (remoteState.updateRequired) {
            UpdateRequiredScreen(onUpdate = device.storeUrl?.let { url -> { runCatching { uriHandler.openUri(url) } } })
            return@TrimioTheme
        }
        val backStack = remember { listOf<Route>(if (settings.onboardingDone) Route.Studio else Route.Onboarding).toMutableStateList() }
        AppNavigation(backStack, settings.language ?: systemLanguage)
    }
}

@Composable
private fun AppNavigation(backStack: SnapshotStateList<Route>, language: Language) {
    val scope = rememberCoroutineScope()
    val settingsRepo = koinInject<SettingsRepository>()
    val models = koinInject<ModelManager>()
    val picker = koinInject<MediaPicker>()
    val projects = koinInject<ProjectRepository>()
    val remoteState by koinInject<RemoteRepository>().state.collectAsStateWithLifecycle()
    val motion = io.trimio.core.designsystem.theme.Trimio.motion
    val rtl = language.isRtl

    fun go(route: Route) = backStack.add(route)
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun replaceTop(route: Route) { backStack[backStack.lastIndex] = route }

    NavDisplay(
        backStack = backStack,
        onBack = ::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        transitionSpec = { forward(motion, rtl) },
        popTransitionSpec = { backward(motion, rtl) },
        predictivePopTransitionSpec = { backward(motion, rtl) },
        entryProvider = entryProvider {
            entry<Route.Onboarding> {
                OnboardingScreen(
                    language = language,
                    defaults = models.catalog.filter { it.isDefault },
                    onLanguage = { lang -> scope.launch { settingsRepo.update { it.copy(language = lang) } } },
                    onDownloadDefaults = models::downloadDefaults,
                    onConnectCloud = {
                        scope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
                        replaceTop(Route.Studio)
                        go(Route.Settings)
                    },
                    onFinish = {
                        scope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
                        replaceTop(Route.Studio)
                    },
                )
            }
            entry<Route.Studio> {
                StudioRoute(
                    viewModel = koinViewModel(),
                    onCreate = { uri, kind -> go(Route.Create(uri, kind)) },
                    onOpen = { p -> go(if (p.status == ProjectStatus.Ready) Route.Editor(p.id) else Route.Build(p.id)) },
                    onGallery = { go(Route.Gallery) },
                    onSettings = { go(Route.Settings) },
                    announcement = if (language == Language.Persian) remoteState.config.announcementFa else remoteState.config.announcementEn,
                )
            }
            entry<Route.Create> { route ->
                CreateRoute(
                    viewModel = koinViewModel(key = route.toString()) { parametersOf(CreateArgs(route.uri, route.kind, route.styleId)) },
                    onBack = ::back,
                    onCreated = { id -> replaceTop(Route.Build(id)) },
                    onSettings = { go(Route.Settings) },
                )
            }
            entry<Route.Build> { route ->
                BuildStreamRoute(koinViewModel(key = route.projectId) { parametersOf(route.projectId) }, onOpen = { replaceTop(Route.Editor(route.projectId)) })
            }
            entry<Route.Editor> { route ->
                EditorRoute(
                    koinViewModel(key = route.projectId) { parametersOf(route.projectId) },
                    onBack = ::back,
                    onExport = { id -> go(Route.Export(id)) },
                )
            }
            entry<Route.Export> { route ->
                ExportRoute(
                    koinViewModel(key = "export-" + route.projectId) { parametersOf(route.projectId) },
                    onBack = ::back,
                    onDone = {
                        backStack.clear()
                        backStack.add(Route.Studio)
                    },
                )
            }
            entry<Route.Gallery> {
                StyleGalleryRoute(koinViewModel(), onBack = ::back, onUse = { styleId ->
                    scope.launch { picker.pick(MediaKind.Video)?.let { go(Route.Create(it.value, MediaKind.Video, styleId)) } }
                })
            }
            entry<Route.Settings> { SettingsRoute(koinViewModel(), onBack = ::back) }
        },
    )
    // Projects are loaded once at start; keep the reference so the graph is warm.
    LaunchedEffect(projects) { projects.load() }
}

/** Forward: the new screen slides in from the reading-direction end, the old one recedes. */
private fun forward(motion: TrimioMotionScheme, rtl: Boolean): ContentTransform {
    val dir = if (rtl) -1 else 1
    return (slideInHorizontally(motion.spatialSlow()) { it * dir / 3 } + fadeIn(motion.effects())) togetherWith
        (fadeOut(motion.effectsFast()) + scaleOut(motion.spatialSlow(), targetScale = 0.94f))
}

private fun backward(motion: TrimioMotionScheme, rtl: Boolean): ContentTransform {
    val dir = if (rtl) -1 else 1
    return fadeIn(motion.effects()) togetherWith
        (slideOutHorizontally(motion.spatialSlow()) { it * dir / 3 } + fadeOut(motion.effectsFast()))
}

/** Maps a BCP-47 language tag to a UI language; the primary market is Persian. */
fun uiLanguageFor(tag: String): Language = Language.fromCode(tag.substringBefore('-').substringBefore('_')) ?: Language.Persian

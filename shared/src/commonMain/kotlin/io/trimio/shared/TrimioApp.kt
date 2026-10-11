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
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.theme.TrimioMotionScheme
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelManager
import io.trimio.feature.create.CreateRoute
import io.trimio.feature.editor.EditorRoute
import io.trimio.feature.gallery.StyleGalleryRoute
import io.trimio.feature.settings.ModelsRoute
import io.trimio.feature.settings.SettingsRoute
import io.trimio.feature.studio.StudioRoute
import io.trimio.shared.di.AppScope
import io.trimio.shared.di.CreateArgs
import io.trimio.shared.di.appModule
import io.trimio.shared.navigation.DirectorGated
import io.trimio.shared.navigation.ExportEntry
import io.trimio.shared.navigation.OnboardingEntry
import io.trimio.shared.navigation.ProduceEntry
import io.trimio.shared.navigation.ProductionProjects
import io.trimio.shared.navigation.ProductionUi
import io.trimio.shared.navigation.ResultEntry
import io.trimio.shared.navigation.Route
import io.trimio.shared.ui.TrimioAppTheme
import io.trimio.shared.ui.UpdateRequiredScreen
import io.trimio.shared.ui.paperFor
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
 * in settings. [onSystemBars] hears whether the app is dark, so the platform can colour its bars.
 * Requires [initTrimio] to have run.
 */
@Composable
fun TrimioApp(systemLanguage: Language, systemPreferences: TrimioPreferences = TrimioPreferences(), onSystemBars: (dark: Boolean) -> Unit = {}) {
    val settingsRepo = koinInject<SettingsRepository>()
    val projects = koinInject<ProjectRepository>()
    val remote = koinInject<RemoteRepository>()
    val models = koinInject<ModelManager>()
    val entitlements = koinInject<Entitlements>()
    val appScope = koinInject<CoroutineScope>(AppScope)
    val device = koinInject<DeviceInfo>()
    val productions = koinInject<ProductionProjects>()
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settingsRepo.load()
        projects.load()
        // Every production run, from any screen (or resumed by the system), is followed into its project.
        productions.start()
        ready = true
        // Config, catalogue and pack updates arrive in the background; the app never waits for them.
        appScope.launch {
            remote.refresh()
            // Newer model releases from the server show up as updates (installed ones keep working).
            models.updateCatalog(remote.models(io.trimio.engine.models.ModelCatalog.all))
            entitlements.billing.refresh()
        }
    }
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    val remoteState by remote.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val dark = !paperFor(settings.theme)
    LaunchedEffect(dark) { onSystemBars(dark) }
    TrimioAppTheme(settings, systemLanguage, systemPreferences) {
        if (!ready) {
            Box(Modifier.fillMaxSize().background(Sig.colors.canvas))
            return@TrimioAppTheme
        }
        if (remoteState.updateRequired) {
            UpdateRequiredScreen(onUpdate = device.storeUrl?.let { url -> { runCatching { uriHandler.openUri(url) } } })
            return@TrimioAppTheme
        }
        val backStack = remember { listOf<Route>(if (settings.onboardingDone) Route.Studio else Route.Onboarding).toMutableStateList() }
        // The canvas behind every screen, so transitions never show through to the window.
        Box(Modifier.fillMaxSize().background(Sig.colors.canvas)) { AppNavigation(backStack, settings.language ?: systemLanguage, systemLanguage) }
    }
}

@Composable
private fun AppNavigation(backStack: SnapshotStateList<Route>, language: Language, systemLanguage: Language) {
    val scope = rememberCoroutineScope()
    val picker = koinInject<MediaPicker>()
    val projects = koinInject<ProjectRepository>()
    val remoteState by koinInject<RemoteRepository>().state.collectAsStateWithLifecycle()
    val device = koinInject<DeviceInfo>()
    val productions = koinInject<ProductionProjects>()
    val previewNotice = if (language == Language.Persian) {
        "نسخهٔ نمایشی: ساخت روی این پلتفرم شبیه‌سازی می‌شود؛ پردازش واقعی ویدیو در اپ اندروید است."
    } else {
        "Preview: builds are simulated on this platform; real video processing runs in the Android app."
    }
    val motion = io.trimio.core.designsystem.theme.Trimio.motion
    val rtl = language.isRtl

    fun go(route: Route) = backStack.add(route)
    fun back() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun replaceTop(route: Route) { backStack[backStack.lastIndex] = route }

    /** A project from home: its result, its live build (or stored failure), or, for an earlier pipeline's, the editor. */
    fun open(p: Project) {
        val persian = language == Language.Persian
        val record = p.production
        val styleName = ProductionUi.styleName(record?.styleId ?: p.styleId?.let(ProductionUi::styleId), persian)
        when {
            record == null && p.status == ProjectStatus.Ready -> go(Route.Editor(p.id))
            record == null -> go(Route.Produce(productions.remake(p, persian), styleName, projectId = p.id))
            p.status == ProjectStatus.Ready -> go(Route.Result(record.buildSeconds, record.version, styleName, p.id))
            else -> productions.requestOf(p)?.let { go(Route.Produce(it, styleName, record.version, p.id)) }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = ::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        transitionSpec = { forward(motion, rtl) },
        popTransitionSpec = { backward(motion, rtl) },
        predictivePopTransitionSpec = { backward(motion, rtl) },
        entryProvider = entryProvider {
            entry<Route.Onboarding> { OnboardingEntry(scope, ::replaceTop, ::go) }
            entry<Route.Studio> {
                StudioRoute(
                    viewModel = koinViewModel { parametersOf(systemLanguage) },
                    onCreate = { uri, kind, prompt, styleId -> go(Route.Create(uri, kind, styleId, prompt)) },
                    onOpen = { id -> projects.get(id)?.let(::open) },
                    onDelete = { id -> projects.get(id)?.let(productions::delete) },
                    onGallery = { go(Route.Gallery) },
                    onSettings = { go(Route.Settings) },
                    onModels = { go(Route.Models) },
                    announcement = (if (language == Language.Persian) remoteState.config.announcementFa else remoteState.config.announcementEn)
                        ?: previewNotice.takeIf { device.previewOnly },
                )
            }
            // The chosen director goes into the order; a cloud build without internet asks first (offline sheet).
            entry<Route.Create> { route -> DirectorGated { gate ->
                CreateRoute(
                    viewModel = koinViewModel(key = route.toString()) { parametersOf(CreateArgs(route.uri, route.kind, route.styleId, route.prompt)) },
                    onBack = ::back,
                    onPickMedia = { kind -> scope.launch { picker.pick(kind)?.let { replaceTop(route.copy(uri = it.value, kind = kind)) } } },
                    onProduce = { state ->
                        val persian = language == Language.Persian
                        gate.start(ProductionUi.request(route.uri, state, persian), (state.info?.durationMs ?: 0L) / 1000f) { request ->
                            val title = ProductionProjects.titleOf(state.prompt, state.kind == MediaKind.Audio, persian)
                            val id = productions.create(request, title, state.info, state.styleId)
                            replaceTop(Route.Produce(request, ProductionUi.styleName(state.styleId, persian), projectId = id))
                        }
                    },
                )
            } }
            entry<Route.Produce> { route ->
                ProduceEntry(
                    route,
                    onResult = { seconds -> replaceTop(Route.Result(seconds, route.version, route.styleName, route.projectId)) },
                    onClose = ::back,
                    onModels = { go(Route.Models) },
                )
            }
            entry<Route.Result> { route ->
                ResultEntry(
                    route,
                    onProduce = { request, projectId, version -> replaceTop(Route.Produce(request, route.styleName, version, projectId)) },
                    onClose = ::back,
                    onExport = { id -> go(Route.Export(id)) },
                )
            }
            entry<Route.Models> { ModelsRoute(koinViewModel(), onBack = ::back) }
            entry<Route.Editor> { route ->
                EditorRoute(
                    koinViewModel(key = route.projectId) { parametersOf(route.projectId) },
                    onBack = ::back,
                    onExport = { id -> go(Route.Export(id)) },
                )
            }
            entry<Route.Export> { route -> ExportEntry(route, onBack = ::back) }
            entry<Route.Gallery> {
                StyleGalleryRoute(koinViewModel(), onBack = ::back, onUse = { styleId ->
                    scope.launch { picker.pick(MediaKind.Video)?.let { go(Route.Create(it.value, MediaKind.Video, styleId)) } }
                })
            }
            entry<Route.Settings> { SettingsRoute(koinViewModel(), onBack = ::back, onModels = { go(Route.Models) }) }
        },
    )
    // Projects are loaded once at start; keep the reference so the graph is warm.
    LaunchedEffect(projects) { projects.load() }
    // Files dropped on the window or opened with Trimio (desktop) start a new edit.
    val incoming = org.koin.compose.getKoin().getOrNull<IncomingMedia>()
    LaunchedEffect(incoming) { incoming?.requests?.collect { go(Route.Create(it.uri, it.kind)) } }
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

package io.trimio.shared.di

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.data.Billing
import io.trimio.core.data.Connectivity
import io.trimio.core.data.GallerySaver
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.StorageInfo
import io.trimio.core.data.DeviceProfile
import io.trimio.core.data.ThermalMonitor
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.MediaUri
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.KeystoreSecretStore
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.llm.cloud.ClaudeLanguageModel
import io.trimio.engine.media.AndroidMediaProbe
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.autopilot.WebKnowledge
import io.trimio.engine.production.AndroidProductionPlatform
import io.trimio.engine.production.AppProductionRunner
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.LocalDirectors
import io.trimio.engine.production.ProductionPlatform
import io.trimio.engine.production.ProductionService
import io.trimio.engine.production.Productions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.files.Path
import org.koin.dsl.module
import java.io.File

/** Engines and services backed by Android: Media3, MediaCodec, whisper.cpp/llama.cpp, Keystore, MediaStore. */
fun androidPlatformModule(
    context: Context,
    appVersion: String,
    versionCode: Int,
    /** The store flavour's billing (Google Play or Cafe Bazaar). */
    billing: Billing,
    storeUrl: String,
    /** Starts the app's user-initiated data-transfer job that keeps model downloads alive. */
    onDownloadsStarted: () -> Unit,
) = module {
    val app = context.applicationContext
    single {
        AppPaths(
            projects = Path(File(app.filesDir, "projects").absolutePath),
            settingsFile = Path(File(app.filesDir, "settings.json").absolutePath),
            // Models live in no-backup storage: multi-gigabyte files must never go to cloud backup.
            models = Path(File(app.noBackupFilesDir, "models").absolutePath),
            packs = Path(File(app.filesDir, "packs").absolutePath),
            remoteCache = Path(File(app.filesDir, "remote.json").absolutePath),
            // Content libraries can reach gigabytes: no-backup storage, like the models.
            contentPacks = Path(File(app.noBackupFilesDir, "packs").absolutePath),
        )
    }
    single<SecretStore> { KeystoreSecretStore(app) }
    single { billing }
    single { ActivityMediaPicker() }
    single<MediaPicker> { get<ActivityMediaPicker>() }
    single<Sharer> { AndroidSharer(app) }
    single {
        DeviceInfo(
            ramGb = ramGb(app), platform = "Android ${android.os.Build.VERSION.RELEASE}", appVersion = appVersion, versionCode = versionCode, storeUrl = storeUrl,
            cores = Runtime.getRuntime().availableProcessors(),
        )
    }
    // Models live in no-backup storage, on the same volume as the app's files.
    single<StorageInfo> { StorageInfo { runCatching { android.os.StatFs(app.noBackupFilesDir.path).availableBytes }.getOrNull() } }
    single<MediaProbe> { AndroidMediaProbe(app) }
    single<ThermalMonitor> { AndroidThermalMonitor(app) }
    single { io.trimio.core.data.CrashLog(Path(File(app.filesDir, "crashes").absolutePath)) }
    single { DeviceProfile.of(ramGb(app), Runtime.getRuntime().availableProcessors(), io.trimio.engine.llm.local.FastCores.count()) }
    single { HttpClient(OkHttp) }
    single { ClaudeFactory { key, model -> ClaudeLanguageModel(key, model) } }
    single { DownloadHooks { active -> if (active) onDownloadsStarted() } }
    // The automatic engine (phase 7.1): whisper.cpp, the installed director, MediaCodec export.
    single { Checkpoints(Path(File(app.filesDir, "productions").absolutePath)) }
    single<ProductionPlatform> {
        val settings = get<SettingsRepository>()
        val models = get<ModelManager>()
        val store = ModelStore(get<AppPaths>().models, get())
        val device = DeviceMemory.phone(get<DeviceInfo>().ramGb)
        // Built per run: the catalogue and the user's picks may change between runs.
        fun directors() = LocalDirectors(
            store, device, models.catalog, preferred = { settings.settings.value.localModelId }, cloud = { get<CloudModels>().firstAvailable() },
        )
        val recognizer = directors().recognizer { settings.settings.value.speechModelId }
        AndroidProductionPlatform(
            app, { recognizer }, { choice -> directors().director(choice) }, device.tier,
            lighter = { id -> directors().lighter(id) }, speech = { directors().speechModel { settings.settings.value.speechModelId } },
        )
    }
    single<AutoEditor> {
        val info = get<DeviceInfo>()
        Productions(
            get(), get(), WebKnowledge.cached(get(), File(app.cacheDir, "web").absolutePath), threads = { get<DeviceProfile>().threadsFor(get<ThermalMonitor>().level.value) },
            app = "Trimio ${info.appVersion} (${info.versionCode}) · ${info.platform}",
        )
    }
    // Exports and results go to Movies/Trimio; the network check is what a cloud run needs.
    single<GallerySaver> { AndroidGallerySaver(app) }
    // The result screen plays the finished edit in the app (Media3).
    single<io.trimio.feature.stream.VideoPlayers> { io.trimio.feature.stream.VideoPlayers { uri -> io.trimio.shared.player.ExoVideoPlayer(app, uri) } }
    single<Connectivity> { AndroidConnectivity(app) }
    // The foreground service finds the runner here when the system restarts it after killing the process.
    ProductionService.runner = { org.koin.core.context.GlobalContext.get().get<AppProductionRunner>() }
}

private fun ramGb(context: Context): Int {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    // totalMem excludes kernel-reserved memory: an "8 GB" phone reports ~7.3 GB.
    return ((info.totalMem + 700_000_000L) / 1_000_000_000L).toInt()
}

/**
 * System pickers bound to the current activity: the Photo Picker for video (no storage permission
 * needed) and the document picker for audio. MainActivity attaches itself in onCreate.
 */
class ActivityMediaPicker : MediaPicker {
    private var video: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var audio: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<MediaUri?>? = null
    private var context: Context? = null

    fun attach(activity: ComponentActivity) {
        context = activity.applicationContext
        video = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { complete(it) }
        audio = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { complete(it) }
    }

    override suspend fun pick(kind: MediaKind): MediaUri? {
        pending?.complete(null)
        val result = CompletableDeferred<MediaUri?>().also { pending = it }
        when (kind) {
            MediaKind.Video -> video?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) ?: return null
            MediaKind.Audio -> audio?.launch(arrayOf("audio/*")) ?: return null
            MediaKind.Image -> video?.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) ?: return null
        }
        return result.await()
    }

    private fun complete(uri: Uri?) {
        // Keep access across restarts so projects can be re-edited and re-exported later.
        if (uri != null) runCatching { context?.contentResolver?.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        pending?.complete(uri?.let { MediaUri(it.toString()) })
        pending = null
    }
}

private class AndroidSharer(private val context: Context) : Sharer {
    override fun share(uri: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uriOf(uri))
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareFiles(files: Map<String, String>, title: String) {
        val dir = File(context.cacheDir, "interchange").apply { mkdirs() }
        sharePaths(files.map { (name, content) -> File(dir, name).apply { writeText(content) }.path }, title)
    }

    override fun sharePaths(paths: List<String>, title: String) {
        val uris = ArrayList(paths.map(::uriOf))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A content:// URI as is; a file in app storage through the app's FileProvider (other apps cannot read paths). */
    private fun uriOf(uri: String): Uri =
        if (uri.startsWith("/")) androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(uri)) else Uri.parse(uri)
}

/**
 * Copies a finished MP4 into Movies/Trimio through MediaStore (no storage permission on Android
 * 10+); the entry stays pending, invisible to other apps, until the copy completes. The app's own
 * file stays (the result and export screens still play and share it).
 */
private class AndroidGallerySaver(private val context: Context) : GallerySaver {
    override suspend fun save(path: String, title: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Trimio" }.take(NAME) + ".mp4"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/Trimio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { resolver.delete(uri, null, null) }.isSuccess
    }

    private companion object {
        const val NAME = 80
    }
}

/** Online when the active network has validated internet (Wi-Fi with a login page does not count). */
private class AndroidConnectivity(private val context: Context) : Connectivity {
    override suspend fun online(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}


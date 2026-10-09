package io.trimio.shared.di

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.MediaUri
import io.trimio.engine.asr.whisper.InstalledWhisperRecognizer
import io.trimio.engine.director.DefaultDirectorModels
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.KeystoreSecretStore
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.llm.cloud.ClaudeLanguageModel
import io.trimio.engine.llm.local.LocalModels
import io.trimio.engine.media.AndroidAudioDecoder
import io.trimio.engine.media.AndroidMediaProbe
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelStore
import io.trimio.engine.render.AndroidVideoExporter
import io.trimio.engine.render.ExportRequest
import io.trimio.engine.render.MediaStorePublisher
import io.trimio.engine.render.RenderFonts
import io.trimio.engine.render.VideoExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.files.Path
import org.koin.dsl.module
import java.io.File

/** Engines and services backed by Android: Media3, MediaCodec, whisper.cpp/llama.cpp, Keystore, MediaStore. */
fun androidPlatformModule(
    context: Context,
    appVersion: String,
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
        )
    }
    single<SecretStore> { KeystoreSecretStore(app) }
    single { ActivityMediaPicker() }
    single<MediaPicker> { get<ActivityMediaPicker>() }
    single<Sharer> { AndroidSharer(app) }
    single { DeviceInfo(ramGb = ramGb(app), platform = "Android ${android.os.Build.VERSION.RELEASE}", appVersion = appVersion) }
    single<MediaProbe> { AndroidMediaProbe(app) }
    single { HttpClient(OkHttp) }
    single { ClaudeFactory { key, model -> ClaudeLanguageModel(key, model) } }
    single { DownloadHooks { active -> if (active) onDownloadsStarted() } }
    single<PipelineFactory> {
        val settings = get<SettingsRepository>()
        val models = get<ModelManager>()
        val store = ModelStore(get<AppPaths>().models, get())
        val ram = get<DeviceInfo>().ramGb
        val decoder = AndroidAudioDecoder(app)
        EnginePipelines(
            probe = get(),
            decoder = decoder,
            recognizer = InstalledWhisperRecognizer {
                val installed = models.catalog.filter { it.kind == ModelKind.Speech && models.isInstalled(it) && it.tier.minRamGb <= ram }
                (installed.firstOrNull { it.id == settings.settings.value.speechModelId } ?: installed.maxByOrNull { it.sizeBytes })?.let(models::pathOf)
            },
            styles = get(),
            directors = DefaultDirectorModels(get<CloudModels>()) { LocalModels(store, ram).best(settings.settings.value.localModelId) },
            library = get(),
            exporter = FontsFirst { fonts -> AndroidVideoExporter(app, fonts) },
            publisher = MediaStorePublisher(app),
            outputPath = { jobId -> File(app.cacheDir, "renders").apply { mkdirs() }.resolve("$jobId.mp4").absolutePath },
            threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6),
        )
    }
}

/** Loads the render font once, then hands it to the real exporter. */
private class FontsFirst(private val create: (FontFamily) -> VideoExporter) : VideoExporter {
    private var delegate: VideoExporter? = null
    override suspend fun export(request: ExportRequest, onFrame: suspend (Int, Int) -> Unit): String {
        val exporter = delegate ?: create(RenderFonts.vazirmatn()).also { delegate = it }
        return exporter.export(request, onFrame)
    }
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
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

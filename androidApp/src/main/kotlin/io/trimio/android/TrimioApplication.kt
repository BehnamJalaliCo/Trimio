package io.trimio.android

import android.app.Application
import io.trimio.core.data.CrashLog
import kotlinx.io.files.Path
import io.trimio.core.data.JobRunner
import io.trimio.shared.di.AppScope
import io.trimio.shared.di.androidPlatformModule
import io.trimio.shared.initTrimio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class TrimioApplication : Application() {
    /** Writes a local report for every crash, then lets the system handle it as usual. */
    private fun installCrashLog(version: String) {
        val log = CrashLog(Path(java.io.File(filesDir, "crashes").absolutePath))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log.record(error, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}", version, System.currentTimeMillis())
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        initTrimio(androidPlatformModule(this, version, onDownloadsStarted = { ModelDownloadJob.schedule(this) }))
        installCrashLog(version)
        Notifications.createChannels(this)

        // Every build or export runs under a foreground service, so it finishes with the screen off.
        val koin = GlobalContext.get()
        val scope = koin.get<CoroutineScope>(AppScope)
        scope.launch {
            koin.get<JobRunner>().active.distinctUntilChangedBy { it?.projectId to it?.kind }.collect { run ->
                if (run != null) RenderService.start(this@TrimioApplication)
            }
        }
    }
}

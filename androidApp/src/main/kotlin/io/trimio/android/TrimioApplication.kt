package io.trimio.android

import android.app.Application
import io.trimio.core.data.CrashLog
import kotlinx.io.files.Path
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.ProductionService
import io.trimio.shared.di.AppScope
import io.trimio.shared.di.androidPlatformModule
import io.trimio.shared.initTrimio
import io.trimio.android.billing.StoreBilling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private fun storeUrl() =
        if (BuildConfig.FLAVOR == "bazaar") "bazaar://details?id=$packageName" else "https://play.google.com/store/apps/details?id=$packageName"

    /** The system's own word that memory runs low: a running edit sheds its optional parts at once ([MemoryPressure]). */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            io.trimio.engine.production.MemoryPressure.warned = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The speed test's measuring process (BenchService) needs only the engines: no app graph, jobs or services.
        if (getProcessName().endsWith(":bench")) return
        val info = packageManager.getPackageInfo(packageName, 0)
        val version = info.versionName ?: "dev"
        val billing = StoreBilling(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        initTrimio(
            androidPlatformModule(this, version, info.longVersionCode.toInt(), billing, storeUrl(), onDownloadsStarted = { ModelDownloadJob.schedule(this) }),
        )
        installCrashLog(version)
        ExitReasons.record(this, CrashLog(Path(java.io.File(filesDir, "crashes").absolutePath)), version)
        Notifications.createChannels(this)
        ModelUpdateJob.schedule(this)

        // Productions (and the editor's renders) run under a foreground service, so they finish with the screen off;
        // a run the system killed is restarted and resumed.
        val koin = GlobalContext.get()
        val scope = koin.get<CoroutineScope>(AppScope)
        ProductionService.icon = R.drawable.ic_stat_trimio
        ProductionService.opens = MainActivity::class.java
        ProductionService.watch(this, koin.get<ProductionRunner>(), scope)
    }
}

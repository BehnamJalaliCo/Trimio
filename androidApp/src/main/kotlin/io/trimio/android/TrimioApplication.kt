package io.trimio.android

import android.app.Application
import io.trimio.core.data.JobRunner
import io.trimio.shared.di.AppScope
import io.trimio.shared.di.androidPlatformModule
import io.trimio.shared.initTrimio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class TrimioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        initTrimio(androidPlatformModule(this, version, onDownloadsStarted = { ModelDownloadJob.schedule(this) }))
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

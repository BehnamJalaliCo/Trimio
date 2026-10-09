package io.trimio.android

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.trimio.core.data.JobRunner
import io.trimio.core.data.ProjectRepository
import io.trimio.core.pipeline.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Keeps a build or export alive while the user leaves the app. Type `mediaProcessing` on
 * Android 15+ (the type made for transcoding work), `dataSync` before that.
 */
class RenderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val koin = GlobalContext.get()
        val runner = koin.get<JobRunner>()
        val projects = koin.get<ProjectRepository>()
        val run = runner.active.value ?: return stop()
        val title = projects.get(run.projectId)?.title ?: getString(R.string.app_name)
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(Notifications.RENDER_ID, Notifications.render(this, title, run.state.value), type)

        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            launch {
                var lastPercent = -1
                run.state.collect { state ->
                    val percent = ((state?.overall ?: 0f) * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        manager.notify(Notifications.RENDER_ID, Notifications.render(this@RenderService, title, state))
                    }
                }
            }
            // Done, failed or cancelled: release the foreground slot.
            run.state.first { it != null && it.status != JobStatus.Running }
            stop()
        }
        scope.launch { runner.active.first { it !== run }; stop() }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RenderService::class.java))
        }
    }
}

package io.trimio.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * User-initiated data-transfer job (Android 14+): the system's sanctioned way to run long
 * user-requested downloads. It keeps the process alive and shows progress while [ModelManager]
 * downloads; the downloads themselves resume from their `.part` files if the job is ever stopped.
 */
class ModelDownloadJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartJob(params: JobParameters): Boolean {
        val models = GlobalContext.get().get<ModelManager>()
        setNotification(params, Notifications.DOWNLOAD_ID, Notifications.download(this, getString(R.string.downloading_models), null), JOB_END_NOTIFICATION_POLICY_REMOVE)
        scope.launch {
            launch {
                models.states.collect { states ->
                    val active = states.values.filterIsInstance<ModelState.Downloading>()
                    if (active.isNotEmpty()) {
                        val fraction = active.map { it.fraction }.average().toFloat()
                        setNotification(params, Notifications.DOWNLOAD_ID, Notifications.download(this@ModelDownloadJob, getString(R.string.downloading_models), fraction), JOB_END_NOTIFICATION_POLICY_REMOVE)
                    }
                }
            }
            models.awaitIdle()
            jobFinished(params, false)
            scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        // Reschedule if downloads are still running when the system stops us.
        return GlobalContext.get().get<ModelManager>().hasActiveDownloads
    }

    companion object {
        private const val JOB_ID = 42

        fun schedule(context: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ModelDownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(3_000_000_000L, 0)
                .build()
            runCatching { context.getSystemService(JobScheduler::class.java).schedule(info) }
        }
    }
}

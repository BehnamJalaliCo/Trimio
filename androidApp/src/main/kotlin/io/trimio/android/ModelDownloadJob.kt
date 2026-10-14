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
        Notifications.downloadsStarted = System.currentTimeMillis()
        setNotification(params, Notifications.DOWNLOAD_ID, Notifications.download(this, getString(R.string.downloading_models), null), JOB_END_NOTIFICATION_POLICY_REMOVE)
        scope.launch {
            launch {
                models.states.collect { states ->
                    val active = states.values.filterIsInstance<ModelState.Downloading>()
                    if (active.isNotEmpty()) {
                        // Bytes over bytes (a big model counts for more than a small one), and how much of how much.
                        val done = active.sumOf { it.bytes }
                        val total = active.sumOf { if (it.fraction > 0f) (it.bytes / it.fraction).toLong() else 0L }
                        val fraction = if (total > 0) (done.toDouble() / total).toFloat() else null
                        val detail = "${persian(active.size)} مدل" + if (total > 0) " · ${gb(done)} از ${gb(total)} گیگ" else ""
                        setNotification(
                            params, Notifications.DOWNLOAD_ID, Notifications.download(this@ModelDownloadJob, getString(R.string.downloading_models), fraction, detail),
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                    }
                }
            }
            // Content libraries («کتابخانه‌ها») download under the same job.
            val packs = GlobalContext.get().getOrNull<io.trimio.engine.models.PackManager>()
            while (models.hasActiveDownloads || packs?.hasActiveDownloads == true) {
                models.awaitIdle()
                packs?.awaitIdle()
                kotlinx.coroutines.delay(250)
            }
            jobFinished(params, false)
            scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        // Reschedule if downloads are still running when the system stops us.
        return GlobalContext.get().get<ModelManager>().hasActiveDownloads ||
            GlobalContext.get().getOrNull<io.trimio.engine.models.PackManager>()?.hasActiveDownloads == true
    }

    private fun persian(n: Int) = io.trimio.engine.production.ProgressNotice.persian(n)

    private fun gb(bytes: Long): String = "%.1f".format(java.util.Locale.ROOT, bytes / GB).map { if (it in '0'..'9') '۰' + (it - '0') else if (it == '.') '٫' else it }.joinToString("")

    companion object {
        private const val JOB_ID = 42
        private const val GB = 1e9

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

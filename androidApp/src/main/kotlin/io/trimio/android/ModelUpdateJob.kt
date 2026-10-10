package io.trimio.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.trimio.core.api.RemoteRepository
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * Twice a day, on any network: asks the catalogue server for newer model releases and, for each
 * release not announced before, posts a notification — separate from app updates, since models
 * improve on their own schedule. Tapping "update" opens the app, which downloads the new release
 * while the installed one keeps working.
 */
class ModelUpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        val koin = GlobalContext.get()
        val remote = koin.get<RemoteRepository>()
        val models = koin.get<ModelManager>()
        scope.launch {
            remote.refresh()
            models.updateCatalog(remote.models(ModelCatalog.all))
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val announced = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty()
            val fresh = models.updates.value.filter { "${it.spec.id}:${it.spec.version}" !in announced }
            if (fresh.isNotEmpty()) {
                Notifications.modelUpdates(this@ModelUpdateJob, fresh)
                prefs.edit().putStringSet(KEY_ANNOUNCED, announced + fresh.map { "${it.spec.id}:${it.spec.version}" }).apply()
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 43
        private const val PREFS = "model-updates"
        private const val KEY_ANNOUNCED = "announced"

        /** Idempotent: keeps one periodic check registered. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ModelUpdateJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(2))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
        }
    }
}

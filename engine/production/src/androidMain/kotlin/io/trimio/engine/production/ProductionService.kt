package io.trimio.engine.production

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps a production alive with the screen off or the app left: a foreground service (type
 * `mediaProcessing` on Android 15+, `dataSync` before) with a progress notification and a partial
 * wake lock (without it the CPU sleeps once the phone is locked and the run stalls), for as long
 * as [ProductionRunner.active] runs. Sticky: when the system kills the process mid-run it restarts
 * the service, which resumes the saved request ([AppProductionRunner.resume]); the checkpointed
 * words and reading skip whisper and the model. Closing the app ends the run ([onTaskRemoved]).
 *
 * The app declares it in its manifest and sets [runner] (and optionally [icon], [opens]) at
 * start-up, before any run; [watch] starts it whenever a run begins.
 */
class ProductionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var awake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = runner?.invoke()
        // A null intent is the system restarting a killed run.
        if (intent == null && runner != null && runner.active.value?.finished != false) runner.resume()
        val current = runner?.active?.value?.takeIf { !it.finished }
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= ANDROID_15) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Foreground first, always: a service started for the foreground that stops before it is ends the app.
        startForeground(NOTIFICATION_ID, notification(this, current ?: ProductionState("")), type)
        if (runner == null || current == null) return stop()
        keepAwake()

        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            launch {
                runner.active.distinctUntilChangedBy { s -> s?.let { it.stage to (it.progress * PERCENT).toInt() } }.collect { s ->
                    if (s != null && !s.finished) manager.notify(NOTIFICATION_ID, notification(this@ProductionService, s))
                }
            }
            // Done, failed or cancelled: release the foreground slot.
            runner.active.first { it == null || it.finished }
            stop()
        }
        return START_STICKY
    }

    /**
     * The creator closed the app (removed it from the recent apps): the run ends there and is not resumed. Leaving the
     * app any other way (home, another app, the screen off) lets it go on.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runner?.invoke()?.cancel()
        stop()
        super.onTaskRemoved(rootIntent)
    }

    /** The CPU keeps working with the screen off; released when the run ends (or after [AWAKE_MS] at most). */
    private fun keepAwake() {
        if (awake?.isHeld == true) return
        awake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trimio:production")
            .apply {
                setReferenceCounted(false)
                acquire(AWAKE_MS)
            }
    }

    private fun release() {
        awake?.takeIf { it.isHeld }?.release()
        awake = null
    }

    private fun stop(): Int {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "production"
        const val NOTIFICATION_ID = 1004
        private const val ANDROID_15 = 35
        private const val PERCENT = 100
        private const val AWAKE_MS = 6 * 60 * 60 * 1000L

        /** The app's runner (set at start-up, read when the system restarts the service). */
        @Volatile var runner: (() -> AppProductionRunner)? = null

        /** The notification's small icon (a monochrome drawable of the app). */
        @Volatile var icon: Int = android.R.drawable.stat_sys_upload

        /** The activity a tap on the notification opens. */
        @Volatile var opens: Class<*>? = null

        /** Persian stage names for the notification line. */
        private val stages = mapOf(
            Stage.Prepare to "آماده‌سازی", Stage.Transcribe to "شنیدن گفتار", Stage.Direct to "کارگردانی",
            Stage.Render to "ساخت ویدیو", Stage.Done to "تمام",
        )

        /** Starts the service whenever [runner] begins a run (call once, from the app scope). */
        fun watch(context: Context, runner: ProductionRunner, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                runner.active.distinctUntilChangedBy { it?.key to (it?.finished ?: true) }.collect { s ->
                    if (s != null && !s.finished) app.startForegroundService(Intent(app, ProductionService::class.java).setAction(ACTION_RUN))
                }
            }
        }

        private const val ACTION_RUN = "io.trimio.production.RUN"

        private fun channel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "ساخت ویدیو", NotificationManager.IMPORTANCE_LOW))
        }

        fun notification(context: Context, s: ProductionState): Notification {
            val percent = (s.progress * PERCENT).toInt()
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle(stages[s.stage] ?: s.stage.name)
                .setContentText("$percent٪")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(PERCENT, percent, false)
            opens?.let { activity ->
                builder.setContentIntent(
                    PendingIntent.getActivity(context, 0, Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE),
                )
            }
            return builder.build()
        }
    }
}

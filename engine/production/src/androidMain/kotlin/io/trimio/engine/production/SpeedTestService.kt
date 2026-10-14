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
import io.trimio.engine.models.SpeedTestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps «سنجش سرعت این دستگاه» going when the creator leaves the app: a foreground service with the test's percentage in
 * its notification and a partial wake lock, for as long as [SpeedTestRunner.state] runs. Closing the app (removing it
 * from the recent apps) ends the test; it is not restarted.
 */
class SpeedTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var awake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runner = runner?.invoke()
        // «توقف» in the notification: the test ends there.
        if (intent?.action == ACTION_STOP) runner?.cancel()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "سنجش سرعت", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= ANDROID_15) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Foreground first, always: a service started for the foreground that stops before it is ends the app.
        startForeground(NOTIFICATION_ID, notification(runner?.state?.value ?: SpeedTestRunner.State()), type)
        if (runner == null || !runner.state.value.running) return stop()
        if (awake?.isHeld != true) {
            awake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trimio:speed-test")
                .apply { setReferenceCounted(false); acquire(AWAKE_MS) }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            launch {
                runner.state.distinctUntilChangedBy { (it.progress * PERCENT).toInt() to it.rows.size }.collect { s ->
                    if (s.running) manager.notify(NOTIFICATION_ID, notification(s))
                }
            }
            val end = runner.state.first { !it.running }
            stop()
            // Finished (not stopped): what every build will run with now.
            if (end.done) {
                val chosen = end.rows.filter { it.best }.joinToString("\n") { "${it.label}: ${it.value}" }.ifBlank { null }
                manager.notify(
                    ProgressNotice.DONE_ID,
                    ProgressNotice.done(this@SpeedTestService, "سنجش سرعت", "بهترین تنظیم پیدا و ذخیره شد", chosen, ProgressNotice.open(this@SpeedTestService)),
                )
            }
        }
        return START_NOT_STICKY
    }

    /** The creator closed the app: the test ends with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runner?.invoke()?.cancel()
        stop()
        super.onTaskRemoved(rootIntent)
    }

    /** The test's notice: its percentage, the clock, the last way measured, and a stop button. */
    private fun notification(s: SpeedTestRunner.State): Notification = ProgressNotice.build(
        this, CHANNEL, work = "سنجش سرعت", title = "سنجش سرعت این دستگاه", detail = s.rows.lastOrNull()?.let { "${it.label}: ${it.value}" },
        progress = s.progress, parts = ProgressNotice.single, startedAt = startedAt, open = ProgressNotice.open(this),
        stop = PendingIntent.getService(this, 2, Intent(this, SpeedTestService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
    )

    private fun stop(): Int {
        awake?.takeIf { it.isHeld }?.release()
        awake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        awake?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "speed-test"
        const val NOTIFICATION_ID = 1005
        private const val ANDROID_15 = 35
        private const val PERCENT = 100
        private const val AWAKE_MS = 60 * 60 * 1000L

        private const val ACTION_STOP = "io.trimio.speed.STOP"

        /** When the test began (the notification's clock counts from it). */
        private var startedAt = System.currentTimeMillis()

        /** The app's speed test (set at start-up). */
        @Volatile var runner: (() -> SpeedTestRunner)? = null

        /** Starts the service whenever the speed test begins (call once, from the app scope). */
        fun watch(context: Context, runner: SpeedTestRunner, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                runner.state.distinctUntilChangedBy { it.running }.collect { s ->
                    if (s.running) {
                        startedAt = System.currentTimeMillis()
                        app.startForegroundService(Intent(app, SpeedTestService::class.java))
                    }
                }
            }
        }
    }
}

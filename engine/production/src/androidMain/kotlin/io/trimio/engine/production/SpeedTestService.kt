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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "سنجش سرعت", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= ANDROID_15) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Foreground first, always: a service started for the foreground that stops before it is ends the app.
        startForeground(NOTIFICATION_ID, notification(runner?.state?.value?.progress ?: 0f), type)
        if (runner == null || !runner.state.value.running) return stop()
        if (awake?.isHeld != true) {
            awake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trimio:speed-test")
                .apply { setReferenceCounted(false); acquire(AWAKE_MS) }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            launch {
                runner.state.distinctUntilChangedBy { (it.progress * PERCENT).toInt() }.collect { s ->
                    if (s.running) manager.notify(NOTIFICATION_ID, notification(s.progress))
                }
            }
            runner.state.first { !it.running }
            stop()
        }
        return START_NOT_STICKY
    }

    /** The creator closed the app: the test ends with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runner?.invoke()?.cancel()
        stop()
        super.onTaskRemoved(rootIntent)
    }

    private fun notification(progress: Float): Notification {
        val percent = (progress * PERCENT).toInt()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(ProductionService.icon)
            .setContentTitle("سنجش سرعت این دستگاه")
            .setContentText("$percent٪")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(PERCENT, percent, false)
        ProductionService.opens?.let { activity ->
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE),
            )
        }
        return builder.build()
    }

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

        /** The app's speed test (set at start-up). */
        @Volatile var runner: (() -> SpeedTestRunner)? = null

        /** Starts the service whenever the speed test begins (call once, from the app scope). */
        fun watch(context: Context, runner: SpeedTestRunner, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                runner.state.distinctUntilChangedBy { it.running }.collect { s ->
                    if (s.running) app.startForegroundService(Intent(app, SpeedTestService::class.java))
                }
            }
        }
    }
}

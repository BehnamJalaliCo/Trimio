package io.trimio.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import io.trimio.core.pipeline.PipelineState
import io.trimio.core.pipeline.StageId

/** Progress notifications for builds and model downloads. */
object Notifications {
    const val RENDER_CHANNEL = "render"
    const val DOWNLOAD_CHANNEL = "downloads"
    const val RENDER_ID = 1001
    const val DOWNLOAD_ID = 1002

    private val brand = Color.parseColor("#7C5CFF")
    private val cyan = Color.parseColor("#3DE8FF")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RENDER_CHANNEL, context.getString(R.string.channel_render), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(DOWNLOAD_CHANNEL, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Build progress. On Android 16+ a segmented ProgressStyle mirrors the in-app ring: one segment
     * per pipeline stage, sized by its weight, filling as the edit is made.
     */
    fun render(context: Context, title: String, state: PipelineState?): Notification {
        val percent = ((state?.overall ?: 0f) * 100).toInt()
        val stage = state?.current?.let { context.getString(R.string.stage_prefix) + " " + it.titleFa }
        val builder = Notification.Builder(context, RENDER_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trimio)
            .setContentTitle(title)
            .setContentText(stage ?: "$percent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
        if (Build.VERSION.SDK_INT >= 36) {
            val segments = (state?.stages?.map { it.id } ?: StageId.entries).map { id ->
                Notification.ProgressStyle.Segment(id.weight * 10).setColor(if (id.ordinal % 2 == 0) brand else cyan)
            }
            builder.setStyle(
                Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(segments)
                    .setProgress(((state?.overall ?: 0f) * segments.sumOf { it.length }).toInt()),
            )
        } else {
            builder.setProgress(100, percent, state == null)
        }
        return builder.build()
    }

    fun download(context: Context, title: String, fraction: Float?): Notification =
        Notification.Builder(context, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trimio)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
}

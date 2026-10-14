package io.trimio.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notifications for model downloads and updates (productions have their own: ProductionService). */
object Notifications {
    const val DOWNLOAD_CHANNEL = "downloads"
    const val DOWNLOAD_ID = 1002
    const val MODEL_UPDATES_CHANNEL = "model-updates"
    const val MODEL_UPDATES_ID = 1003
    const val EXTRA_APPLY_MODEL_UPDATES = "io.trimio.apply_model_updates"
    private const val LEGACY_RENDER_CHANNEL = "render"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The earlier build pipeline's channel: nothing posts to it any more.
        manager.deleteNotificationChannel(LEGACY_RENDER_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(DOWNLOAD_CHANNEL, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(
            NotificationChannel(MODEL_UPDATES_CHANNEL, context.getString(R.string.channel_model_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    /** "A better director is ready": one line per release, an action that updates from the app. */
    fun modelUpdates(context: Context, updates: List<io.trimio.engine.models.ModelUpdate>) {
        val persian = context.resources.configuration.locales[0].language == "fa"
        val lines = updates.map { u ->
            val title = if (persian) u.spec.titleFa else u.spec.titleEn
            val notes = (if (persian) u.spec.notesFa else u.spec.notesEn)?.let { " · $it" }.orEmpty()
            context.getString(R.string.model_update_line, title, u.spec.version) + notes
        }
        val apply = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_APPLY_MODEL_UPDATES, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, MODEL_UPDATES_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trimio)
            .setContentTitle(context.getString(R.string.model_update_title))
            .setContentText(lines.first() + if (lines.size > 1) " +${lines.size - 1}" else "")
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(null, context.getString(R.string.model_update_action), apply).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(MODEL_UPDATES_ID, notification)
    }

    /** Model and library downloads, in the same design as the build's and the speed test's notices. */
    fun download(context: Context, title: String, fraction: Float?, detail: String? = null): Notification =
        io.trimio.engine.production.ProgressNotice.build(
            context, DOWNLOAD_CHANNEL, work = context.getString(R.string.channel_downloads), title = title, detail = detail,
            progress = fraction, parts = io.trimio.engine.production.ProgressNotice.single, startedAt = downloadsStarted, open = openApp(context),
        )

    /** When the downloads began (the notification's clock counts from it). */
    var downloadsStarted = System.currentTimeMillis()
}

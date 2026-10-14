package io.trimio.engine.production

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Trimio's notifications for long work — the build, the speed test, model downloads — in one design: the brand's orange,
 * the percentage in Persian digits, what is happening now, the time it has been working, and a stop button where the
 * work can stop. On Android 16+ the system's progress style: the work's stages as segments of the bar in their own
 * shades, points where one gives way to the next, the app's mark riding the bar, and a live update (the percentage as a
 * chip in the status bar) where the phone allows it. Earlier Android: the classic bar in the brand colour.
 */
object ProgressNotice {
    /** A part of the work: its share of the bar and its colour. */
    class Part(val share: Float, val color: Int)

    /** The build's four stages, in the shares its percentage gives them ([Stage.weight]): warm to lime as it nears the end. */
    val build: List<Part> = Stage.entries.filter { it.weight > 0f }.map { s ->
        Part(
            s.weight,
            when (s) {
                Stage.Prepare -> PEACH
                Stage.Transcribe -> CORAL
                Stage.Direct -> ORANGE
                else -> LIME
            },
        )
    }

    /** One stretch of work (the speed test, a download): the brand orange throughout. */
    val single: List<Part> = listOf(Part(1f, ORANGE))

    @Suppress("LongParameterList")
    fun build(
        context: Context,
        channel: String,
        /** The work («ساخت ویدیو»), shown in the header beside the app's name. */
        work: String,
        /** What it is doing now («کارگردانی»). */
        title: String,
        detail: String?,
        /** 0..1, null while it cannot tell. */
        progress: Float?,
        parts: List<Part>,
        startedAt: Long,
        open: PendingIntent?,
        stop: PendingIntent? = null,
    ): Notification {
        val percent = progress?.let { "${persian((it.coerceIn(0f, 1f) * PERCENT).toInt())}٪" }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(ProductionService.icon)
            .setColor(ORANGE)
            .setSubText(work)
            .setContentTitle(listOfNotNull(title, percent).joinToString(" · "))
            .setContentText(detail)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        open?.let(builder::setContentIntent)
        stop?.let { builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, ProductionService.icon), "توقف", it).build()) }
        if (Build.VERSION.SDK_INT >= ANDROID_16) {
            builder.setStyle(style(context, progress, parts))
            builder.setRequestPromotedOngoing(true)
            percent?.let(builder::setShortCriticalText)
        } else {
            builder.setProgress(PERCENT, ((progress ?: 0f) * PERCENT).toInt(), progress == null)
        }
        return builder.build()
    }

    /** The finished work's notice: tap opens the app, swipe dismisses it. */
    fun done(context: Context, work: String, title: String, detail: String?, open: PendingIntent?): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "پایان کار", NotificationManager.IMPORTANCE_DEFAULT))
        return Notification.Builder(context, DONE_CHANNEL)
            .setSmallIcon(ProductionService.icon)
            .setColor(ORANGE)
            .setSubText(work)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .apply { open?.let(::setContentIntent) }
            .build()
    }

    /** Tapping the notice opens the app where it was. */
    fun open(context: Context): PendingIntent? = ProductionService.opens?.let { activity ->
        PendingIntent.getActivity(context, 0, Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    }

    @androidx.annotation.RequiresApi(ANDROID_16)
    private fun style(context: Context, progress: Float?, parts: List<Part>): Notification.ProgressStyle {
        val total = parts.sumOf { it.share.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
        val lengths = parts.map { (it.share / total * SCALE).toInt().coerceAtLeast(1) }
        var edge = 0
        val points = lengths.dropLast(1).map { length -> edge += length; Notification.ProgressStyle.Point(edge).setColor(INK) }
        return Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(parts.zip(lengths).map { (p, length) -> Notification.ProgressStyle.Segment(length).setColor(p.color) })
            .setProgressPoints(points)
            .setProgressTrackerIcon(Icon.createWithResource(context, ProductionService.icon))
            .setProgressIndeterminate(progress == null)
            .setProgress(((progress ?: 0f).coerceIn(0f, 1f) * lengths.sum()).toInt())
    }

    fun persian(n: Int): String = n.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    const val DONE_CHANNEL = "done"
    const val DONE_ID = 1006
    private const val PERCENT = 100
    private const val SCALE = 1000
    private const val ANDROID_16 = 36

    // The app's own palette: orange (action), its warmer shades, lime (emphasis), and the paper ink for the points.
    private const val ORANGE = 0xFFFF5B2E.toInt()
    private const val CORAL = 0xFFFF8A5C.toInt()
    private const val PEACH = 0xFFFFB59A.toInt()
    private const val LIME = 0xFFD7FF3A.toInt()
    private const val INK = 0xFFF2EDE4.toInt()
}

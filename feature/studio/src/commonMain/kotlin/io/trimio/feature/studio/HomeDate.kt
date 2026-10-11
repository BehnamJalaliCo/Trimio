package io.trimio.feature.studio

import androidx.compose.runtime.Composable
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.text.Language
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Dates as people read them: the Solar Hijri calendar in Persian, the Gregorian one in English. */
internal object HomeDate {

    /** A Gregorian date in the Solar Hijri calendar (year, month 1–12, day). */
    fun jalali(date: LocalDate): Triple<Int, Int, Int> {
        val gy = date.year
        val gm = date.month.ordinal + 1
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355_666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + date.day + MONTH_START[gm - 1]
        var jy = -1595 + 33 * (days / 12_053)
        days %= 12_053
        jy += 4 * (days / 1_461)
        days %= 1_461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        return if (days < 186) Triple(jy, 1 + days / 31, 1 + days % 31) else Triple(jy, 7 + (days - 186) / 30, 1 + (days - 186) % 30)
    }

    fun localDate(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date

    /** "سه‌شنبه، ۱۷ مهر" / "Tuesday, 9 October". */
    fun line(date: LocalDate, persian: Boolean): String = if (persian) {
        val (_, m, d) = jalali(date)
        "${WEEKDAYS_FA.getValue(date.dayOfWeek)}، $d ${MONTHS_FA[m - 1]}"
    } else {
        val day = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        "$day, ${date.day} $month"
    }

    private val MONTH_START = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    private val MONTHS_FA = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private val WEEKDAYS_FA = mapOf(
        DayOfWeek.SATURDAY to "شنبه", DayOfWeek.SUNDAY to "یکشنبه", DayOfWeek.MONDAY to "دوشنبه", DayOfWeek.TUESDAY to "سه‌شنبه",
        DayOfWeek.WEDNESDAY to "چهارشنبه", DayOfWeek.THURSDAY to "پنجشنبه", DayOfWeek.FRIDAY to "جمعه",
    )
}

/** Today's line on home, in the UI language's calendar and digits. */
@Composable
internal fun todayLine(now: Long): String {
    return localizedNumber(HomeDate.line(HomeDate.localDate(now), Trimio.language == Language.Persian))
}

/** "همین الان", "۳ ساعت پیش", "دیروز", "۳ روز پیش", "هفتهٔ پیش"… by calendar days, not 24-hour blocks. */
@Composable
internal fun whenLine(then: Long, now: Long): String {
    val days = (HomeDate.localDate(now).toEpochDays() - HomeDate.localDate(then).toEpochDays()).toInt()
    val minutes = ((now - then) / MINUTE).coerceAtLeast(0)
    return localizedNumber(
        when {
            days <= 0 && minutes < 1 -> tr("همین الان", "just now")
            days <= 0 && minutes < HOUR -> tr("$minutes دقیقه پیش", "$minutes min ago")
            days <= 0 -> tr("${minutes / HOUR} ساعت پیش", "${minutes / HOUR} h ago")
            days == 1 -> tr("دیروز", "yesterday")
            days < WEEK -> tr("$days روز پیش", "$days days ago")
            days < 2 * WEEK -> tr("هفتهٔ پیش", "last week")
            days < MONTH -> tr("${days / WEEK} هفته پیش", "${days / WEEK} weeks ago")
            else -> tr("${days / MONTH} ماه پیش", "${days / MONTH} months ago")
        },
    )
}

/** "۰۰:۴۲" — minutes and seconds of an edit. */
@Composable
internal fun clockLine(ms: Long): String {
    val s = (ms / 1000).toInt()
    return localizedNumber("${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}")
}

private const val MINUTE = 60_000L
private const val HOUR = 60L
private const val WEEK = 7
private const val MONTH = 30

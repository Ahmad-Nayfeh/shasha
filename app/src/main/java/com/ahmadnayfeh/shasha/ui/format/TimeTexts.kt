package com.ahmadnayfeh.shasha.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import com.ahmadnayfeh.shasha.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Times of day and durations as the app says them: 12-hour clock with «ص»/«م» ("7:00 ص", "10:30 م"),
 * minutes and hours counted in Arabic ("45 دقيقة", "ساعتان و10 دقائق"), always with Western digits.
 */
class TimeTexts(private val resources: Resources, private val formats: Formats) {

    /** [minuteOfDay] 0..1440 (1440 is the midnight that ends the day) → "7:00 ص". */
    fun time(minuteOfDay: Int): String {
        val minutes = Math.floorMod(minuteOfDay, 24 * 60)
        val hour = minutes / 60
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val digits = String.format(Locale.ROOT, "%d:%02d", hour12, minutes % 60)
        return resources.getString(if (hour < 12) R.string.clock_am else R.string.clock_pm, digits)
    }

    /**
     * When something happens again, from [nowMs]: "الساعة 7:00 ص" today, "غداً الساعة 7:00 ص", or with the
     * weekday further away ("يوم الأحد الساعة 7:00 ص").
     */
    fun whenNext(atMs: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = time(at.hour * 60 + at.minute)
        val day = at.toLocalDate()
        return when (day) {
            today -> resources.getString(R.string.when_today, time)
            today.plusDays(1) -> resources.getString(R.string.when_tomorrow, time)
            else -> resources.getString(R.string.when_weekday, weekday(day), time)
        }
    }

    fun weekday(day: LocalDate): String = resources.getStringArray(R.array.weekdays)[day.dayOfWeek.value - 1]

    /** "45 دقيقة", "ساعة واحدة", "ساعتان و10 دقائق". */
    fun duration(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> formats.count(R.array.count_minutes, rest)
            rest == 0L -> formats.count(R.array.count_hours, hours)
            else -> resources.getString(R.string.duration_hours_minutes, formats.count(R.array.count_hours, hours), formats.count(R.array.count_minutes, rest))
        }
    }

    /** The short form for tight places (chart labels): "45 د", "2 س 10 د". */
    fun durationShort(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> resources.getString(R.string.duration_short_minutes, rest.toString())
            rest == 0L -> resources.getString(R.string.duration_short_hours, hours.toString())
            else -> resources.getString(R.string.duration_short_both, hours.toString(), rest.toString())
        }
    }
}

@Composable
fun rememberTimeTexts(): TimeTexts {
    val resources = LocalResources.current
    val formats = rememberFormats()
    return remember(resources, formats) { TimeTexts(resources, formats) }
}

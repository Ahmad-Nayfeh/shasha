package com.ahmadnayfeh.shasha.mobile.common

import android.content.Context
import com.ahmadnayfeh.shasha.mobile.R
import java.util.Locale

/**
 * Numbers and times as the screens show them. Digits are always the Western ones (also in Arabic): numbers are built
 * from `toString()` and passed to the texts as `%1$s`, never `%d`, which an Arabic locale would print as Arabic-Indic.
 */
object Formatters {

    /** "3:07", "1:02:03". */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** The player's clock for a position in milliseconds. */
    fun clock(ms: Long): String = duration(ms.coerceAtLeast(0) / 1000)

    private fun two(n: Long) = if (n < 10) "0$n" else n.toString()

    /** 950 → "950", 1200 → "1.2K", 15300 → "15K", 3_400_000 → "3.4M". */
    fun compactCount(context: Context, count: Long): String {
        val n = count.coerceAtLeast(0)
        return when {
            n < 1_000 -> n.toString()
            n < 1_000_000 -> context.getString(R.string.count_thousand, scaled(n, 1_000))
            n < 1_000_000_000 -> context.getString(R.string.count_million, scaled(n, 1_000_000))
            else -> context.getString(R.string.count_billion, scaled(n, 1_000_000_000))
        }
    }

    /** One decimal below 10 ("1.2"), none from 10 ("15"); a trailing ".0" is dropped. */
    private fun scaled(n: Long, unit: Long): String {
        val tenths = n * 10 / unit
        return if (tenths >= 100) (tenths / 10).toString()
        else if (tenths % 10 == 0L) (tenths / 10).toString()
        else "${tenths / 10}.${tenths % 10}"
    }

    fun views(context: Context, count: Long): String = context.getString(R.string.views_count, compactCount(context, count))

    fun subscribers(context: Context, count: Long): String =
        context.getString(R.string.subscribers_count, compactCount(context, count))

    fun videos(context: Context, count: Long): String = context.getString(R.string.videos_count, compactCount(context, count))

    /** "5 minutes ago" … "2 years ago", from the same plural texts in both languages. */
    fun relativeTime(context: Context, epochMs: Long, nowMs: Long): String {
        val minutes = (nowMs - epochMs).coerceAtLeast(0) / 60_000
        val days = minutes / (60 * 24)
        fun plural(id: Int, n: Long) = context.resources.getQuantityString(id, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n.toString())
        return when {
            minutes < 1 -> context.getString(R.string.time_just_now)
            minutes < 60 -> plural(R.plurals.time_minutes_ago, minutes)
            minutes < 60 * 24 -> plural(R.plurals.time_hours_ago, minutes / 60)
            days < 7 -> plural(R.plurals.time_days_ago, days)
            days < 30 -> plural(R.plurals.time_weeks_ago, days / 7)
            days < 365 -> plural(R.plurals.time_months_ago, days / 30)
            else -> plural(R.plurals.time_years_ago, days / 365)
        }
    }

    /** "850 KB", "12.4 MB", "1.3 GB" (units are the same in both languages). */
    fun fileSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        return when {
            b < 1024 -> "${bytes.coerceAtLeast(0)} B"
            b < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", b / 1024)
            b < 1024.0 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", b / (1024 * 1024))
            else -> String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024))
        }
    }

    /** "1080p", "720p60". */
    fun quality(height: Int, fps: Int = 0): String = if (fps > 30) "${height}p$fps" else "${height}p"
}

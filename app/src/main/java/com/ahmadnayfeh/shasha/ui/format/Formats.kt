package com.ahmadnayfeh.shasha.ui.format

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import com.ahmadnayfeh.shasha.R
import java.util.Locale

/**
 * The app's Arabic phrases with numbers ("منذ 3 ساعات", "1.2 مليون مشترك", "12 فيديو"), always with
 * Western digits. One instance per screen ([rememberFormats]); string arrays are read once and cached,
 * so cards can format while composing without touching resources again.
 */
@Stable
class Formats(private val resources: Resources) {
    private val cache = HashMap<Int, Array<String>>()

    private fun forms(@ArrayRes id: Int): Array<String> = cache.getOrPut(id) { resources.getStringArray(id) }

    /** "منذ 3 ساعات"; "الآن" under a minute. */
    fun ago(thenMs: Long, nowMs: Long): String {
        val elapsed = Elapsed.between(thenMs, nowMs) ?: return resources.getString(R.string.time_now)
        val id = when (elapsed.unit) {
            Elapsed.TimeUnit.MINUTES -> R.array.ago_minutes
            Elapsed.TimeUnit.HOURS -> R.array.ago_hours
            Elapsed.TimeUnit.DAYS -> R.array.ago_days
            Elapsed.TimeUnit.WEEKS -> R.array.ago_weeks
            Elapsed.TimeUnit.MONTHS -> R.array.ago_months
            Elapsed.TimeUnit.YEARS -> R.array.ago_years
        }
        return ArabicPlural.format(forms(id), elapsed.count)
    }

    /** A counted phrase from a [PluralForm]-ordered string array, e.g. R.array.count_videos → "12 فيديو". */
    fun count(@ArrayRes id: Int, n: Long): String = ArabicPlural.format(forms(id), n)

    /** "1.2 مليون مشترك", "350 ألف مشترك", "812 مشترك". */
    fun subscribers(n: Long): String =
        compactNumber(n)?.let { resources.getString(R.string.subscribers_compact, it) } ?: count(R.array.count_subscribers, n)

    /** "1.2 مليون", "35 ألف"; null below a thousand (the caller counts those exactly). */
    fun compactNumber(n: Long): String? {
        val (value, unit) = when {
            n >= 1_000_000_000 -> n / 1e9 to R.string.number_billions
            n >= 1_000_000 -> n / 1e6 to R.string.number_millions
            n >= 1_000 -> n / 1e3 to R.string.number_thousands
            else -> return null
        }
        return resources.getString(unit, oneDecimal(value))
    }

    private fun oneDecimal(value: Double): String {
        // 1.2 / 12 / 350: one decimal only below ten, and never "1.0".
        val text = if (value < 10) String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10) else Math.floor(value).toLong().toString()
        return text.removeSuffix(".0")
    }
}

@Composable
fun rememberFormats(): Formats {
    val resources = LocalResources.current
    return remember(resources) { Formats(resources) }
}

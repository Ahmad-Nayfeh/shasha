package com.ahmadnayfeh.shasha.mobile.common

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** Numbers and times in English. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class FormattersEnglishTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun durationsReadLikeAClock() {
        assertThat(Formatters.duration(0)).isEqualTo("0:00")
        assertThat(Formatters.duration(65)).isEqualTo("1:05")
        assertThat(Formatters.duration(3_599)).isEqualTo("59:59")
        assertThat(Formatters.duration(3_725)).isEqualTo("1:02:05")
        assertThat(Formatters.duration(-5)).isEqualTo("0:00")
        assertThat(Formatters.clock(125_900)).isEqualTo("2:05")
    }

    @Test
    fun countsAreShortened() {
        assertThat(Formatters.compactCount(context, 0)).isEqualTo("0")
        assertThat(Formatters.compactCount(context, 999)).isEqualTo("999")
        assertThat(Formatters.compactCount(context, 1_000)).isEqualTo("1K")
        assertThat(Formatters.compactCount(context, 1_234)).isEqualTo("1.2K")
        assertThat(Formatters.compactCount(context, 15_300)).isEqualTo("15K")
        assertThat(Formatters.compactCount(context, 999_999)).isEqualTo("999K")
        assertThat(Formatters.compactCount(context, 1_000_000)).isEqualTo("1M")
        assertThat(Formatters.compactCount(context, 3_400_000)).isEqualTo("3.4M")
        assertThat(Formatters.compactCount(context, 2_150_000_000)).isEqualTo("2.1B")
        assertThat(Formatters.views(context, 1_500)).isEqualTo("1.5K views")
        assertThat(Formatters.subscribers(context, 12_345)).isEqualTo("12K subscribers")
    }

    @Test
    fun relativeTimeCountsInTheBiggestWholeUnit() {
        val now = 10_000 * DAY
        assertThat(Formatters.relativeTime(context, now - 20_000, now)).isEqualTo("Just now")
        assertThat(Formatters.relativeTime(context, now - MINUTE, now)).isEqualTo("1 minute ago")
        assertThat(Formatters.relativeTime(context, now - 5 * MINUTE, now)).isEqualTo("5 minutes ago")
        assertThat(Formatters.relativeTime(context, now - 3 * HOUR, now)).isEqualTo("3 hours ago")
        assertThat(Formatters.relativeTime(context, now - DAY, now)).isEqualTo("1 day ago")
        assertThat(Formatters.relativeTime(context, now - 6 * DAY, now)).isEqualTo("6 days ago")
        assertThat(Formatters.relativeTime(context, now - 14 * DAY, now)).isEqualTo("2 weeks ago")
        assertThat(Formatters.relativeTime(context, now - 65 * DAY, now)).isEqualTo("2 months ago")
        assertThat(Formatters.relativeTime(context, now - 800 * DAY, now)).isEqualTo("2 years ago")
        // A date in the future (a clock that is off) is "just now", not a negative amount.
        assertThat(Formatters.relativeTime(context, now + DAY, now)).isEqualTo("Just now")
    }

    @Test
    fun sizesAndQualities() {
        assertThat(Formatters.fileSize(512)).isEqualTo("512 B")
        assertThat(Formatters.fileSize(850 * 1024)).isEqualTo("850 KB")
        assertThat(Formatters.fileSize(13_000_000)).isEqualTo("12.4 MB")
        assertThat(Formatters.fileSize(1_400_000_000)).isEqualTo("1.30 GB")
        assertThat(Formatters.quality(1080)).isEqualTo("1080p")
        assertThat(Formatters.quality(720, 60)).isEqualTo("720p60")
        assertThat(Formatters.quality(720, 30)).isEqualTo("720p")
    }
}

/** The same in Arabic: Western digits, and Arabic's six plural forms. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ar")
class FormattersArabicTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun countsUseArabicWordsWithWesternDigits() {
        assertThat(Formatters.compactCount(context, 1_234)).isEqualTo("1.2 ألف")
        assertThat(Formatters.compactCount(context, 3_400_000)).isEqualTo("3.4 مليون")
        assertThat(Formatters.compactCount(context, 2_000_000_000)).isEqualTo("2 مليار")
        assertThat(Formatters.views(context, 1_500)).isEqualTo("1.5 ألف مشاهدة")
    }

    @Test
    fun relativeTimeFollowsArabicsPluralForms() {
        val now = 10_000 * DAY
        assertThat(Formatters.relativeTime(context, now - 20_000, now)).isEqualTo("الآن")
        assertThat(Formatters.relativeTime(context, now - DAY, now)).isEqualTo("قبل يوم")
        assertThat(Formatters.relativeTime(context, now - 2 * DAY, now)).isEqualTo("قبل يومين")
        assertThat(Formatters.relativeTime(context, now - 3 * DAY, now)).isEqualTo("قبل 3 أيام")
        assertThat(Formatters.relativeTime(context, now - 5 * HOUR, now)).isEqualTo("قبل 5 ساعات")
        assertThat(Formatters.relativeTime(context, now - 11 * HOUR, now)).isEqualTo("قبل 11 ساعة")
        assertThat(Formatters.relativeTime(context, now - 14 * DAY, now)).isEqualTo("قبل أسبوعين")
        assertThat(Formatters.relativeTime(context, now - 90 * DAY, now)).isEqualTo("قبل 3 أشهر")
        assertThat(Formatters.relativeTime(context, now - 800 * DAY, now)).isEqualTo("قبل سنتين")
    }
}

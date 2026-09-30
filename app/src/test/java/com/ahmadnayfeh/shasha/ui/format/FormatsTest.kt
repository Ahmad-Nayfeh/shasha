package com.ahmadnayfeh.shasha.ui.format

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.content.Context

/**
 * The real string resources, on a TV whose system language is Arabic (Saudi Arabia) and on one in
 * English: the phrases are the same Arabic, with Western digits, either way.
 */
@RunWith(AndroidJUnit4::class)
class FormatsTest {
    private val formats get() = Formats(ApplicationProvider.getApplicationContext<Context>().resources)
    private val now = 1_790_596_800_000L
    private val hour = 3_600_000L

    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA-$TV_1080P")
    fun relativeTimesInArabicLocale() = checkRelativeTimes()

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-$TV_1080P")
    fun relativeTimesInEnglishLocale() = checkRelativeTimes()

    private fun checkRelativeTimes() {
        val f = formats
        assertThat(f.ago(now - 20_000, now)).isEqualTo("الآن")
        assertThat(f.ago(now - 60_000, now)).isEqualTo("منذ دقيقة")
        assertThat(f.ago(now - 3 * hour, now)).isEqualTo("منذ 3 ساعات")
        assertThat(f.ago(now - 2 * hour, now)).isEqualTo("منذ ساعتين")
        assertThat(f.ago(now - 11 * hour, now)).isEqualTo("منذ 11 ساعة")
        assertThat(f.ago(now - 2 * 24 * hour, now)).isEqualTo("منذ يومين")
        assertThat(f.ago(now - 3 * 7 * 24 * hour, now)).isEqualTo("منذ 3 أسابيع")
        assertThat(f.ago(now - 11L * 30 * 24 * hour, now)).isEqualTo("منذ 11 شهراً")
        assertThat(f.ago(now - 5L * 365 * 24 * hour, now)).isEqualTo("منذ 5 سنوات")
    }

    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA-$TV_1080P")
    fun countsAndSubscribers() {
        val f = formats
        assertThat(f.count(R.array.count_videos, 1)).isEqualTo("فيديو واحد")
        assertThat(f.count(R.array.count_videos, 24)).isEqualTo("24 فيديو")
        assertThat(f.count(R.array.count_channels, 2)).isEqualTo("قناتان")
        assertThat(f.count(R.array.count_channels, 7)).isEqualTo("7 قنوات")
        assertThat(f.subscribers(1_250_000)).isEqualTo("1.2 مليون مشترك")
        assertThat(f.subscribers(1_000_000)).isEqualTo("1 مليون مشترك")
        assertThat(f.subscribers(38_400)).isEqualTo("38 ألف مشترك")
        assertThat(f.subscribers(2_500)).isEqualTo("2.5 ألف مشترك")
        // The counted noun follows the number's last two digits: 812 like 12, 800 like 100.
        assertThat(f.subscribers(812)).isEqualTo("812 مشتركاً")
        assertThat(f.subscribers(800)).isEqualTo("800 مشترك")
        assertThat(f.subscribers(40)).isEqualTo("40 مشتركاً")
        assertThat(f.subscribers(3)).isEqualTo("3 مشتركين")
        assertThat(f.subscribers(3_100_000_000)).isEqualTo("3.1 مليار مشترك")
    }
}

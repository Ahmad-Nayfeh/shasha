package com.ahmadnayfeh.shasha.ui.format

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_DEVICE
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.ui.theme.AppLocale
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.content.Context

/**
 * The real string resources in the app's language (MainActivity applies it whatever the TV's), on a TV whose system
 * language is Arabic (Saudi Arabia) and on one in English: the app in Arabic shows the same Arabic phrases either way,
 * with Western digits; the app in English shows English ones.
 */
@RunWith(AndroidJUnit4::class)
class FormatsTest {
    private fun formats(language: AppLanguage = AppLanguage.ARABIC) =
        Formats(AppLocale.localize(ApplicationProvider.getApplicationContext<Context>(), language).resources)

    private val now = 1_790_596_800_000L
    private val hour = 3_600_000L

    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA-$TV_DEVICE")
    fun relativeTimesInArabicLocale() = checkRelativeTimes()

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-$TV_DEVICE")
    fun relativeTimesInEnglishLocale() = checkRelativeTimes()

    private fun checkRelativeTimes() {
        val f = formats()
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
    @Config(sdk = [35], qualifiers = "ar-rSA-$TV_DEVICE")
    fun countsAndSubscribers() {
        val f = formats()
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

    /** «اللغة» English, on an Arabic TV: English phrases, one/other forms, Western digits. */
    @Test
    @Config(sdk = [35], qualifiers = "ar-rSA-$TV_DEVICE")
    fun theAppInEnglish() {
        val f = formats(AppLanguage.ENGLISH)
        assertThat(f.ago(now - 20_000, now)).isEqualTo("just now")
        assertThat(f.ago(now - 60_000, now)).isEqualTo("1 minute ago")
        assertThat(f.ago(now - 2 * hour, now)).isEqualTo("2 hours ago")
        assertThat(f.ago(now - 11 * hour, now)).isEqualTo("11 hours ago")
        assertThat(f.count(R.array.count_videos, 1)).isEqualTo("1 video")
        assertThat(f.count(R.array.count_videos, 24)).isEqualTo("24 videos")
        assertThat(f.subscribers(1_250_000)).isEqualTo("1.2M subscribers")
        assertThat(f.subscribers(3)).isEqualTo("3 subscribers")
    }
}

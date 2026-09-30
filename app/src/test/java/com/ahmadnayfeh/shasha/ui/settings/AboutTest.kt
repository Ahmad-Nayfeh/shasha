package com.ahmadnayfeh.shasha.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.policy.PinAttemptLimiter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * «حول»: SponsorBlock's attribution word for word, the terms-of-use note, and a licence for every library; and the
 * settings' own notes say what the rules really do.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AboutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sponsorBlocksAttribution_isItsOwnSentenceExactly() {
        // SponsorBlock's attribution template (gist ajayyy/4b27dfc66e33941a45aeaadccb51de71), final period included.
        assertThat(context.getString(R.string.sponsorblock_attribution_exact))
            .isEqualTo("Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from https://sponsor.ajay.app/.")
        assertThat(context.getString(R.string.sponsorblock_attribution)).contains("CC BY-NC-SA 4.0")
    }

    @Test
    fun thePinNote_namesTheRealLockouts_fromFiveMinutesToFourHours() {
        val steps = PinAttemptLimiter.LOCKOUT_STEPS_MILLIS
        assertThat(steps.first()).isEqualTo(5 * 60_000L)
        assertThat(steps.last()).isEqualTo(4 * 60 * 60_000L)
        val texts = BehaviorTexts(context.resources, Formats(context.resources), TimeTexts(context.resources, Formats(context.resources)))
        assertThat(texts.pinNote(Behavior.DEFAULT)).contains("و5 محاولات خاطئة متتالية توقفه من 5 دقائق حتى 4 ساعات")
        // The parent's own brake: 3 wrong, first lockout 10 minutes, the longest 48 times that.
        assertThat(texts.pinNote(Behavior(pinMaxFailures = 3, pinFirstLockoutMinutes = 10))).contains("و3 محاولات خاطئة متتالية توقفه من 10 دقائق حتى 8 ساعات")
        assertThat(context.getString(R.string.protection_limits_pin_lockout)).contains("من 5 دقائق حتى 4 ساعات")
    }

    @Test
    fun noTextSpeaksOfYouTubesTerms_orOfPersonalUseOnly() {
        // Owner decision (v1.1): the app's own texts say nothing about YouTube's terms of service.
        val fields = R.string::class.java.fields
        val texts = fields.map { context.getString(it.getInt(null)) }
        assertThat(texts).isNotEmpty()
        for (text in texts) {
            assertThat(text).doesNotContain("شروط خدمة")
            assertThat(text).doesNotContain("للاستخدام الشخصي")
            assertThat(text).doesNotContain("الاستخدام الشخصي")
            assertThat(text.lowercase()).doesNotContain("terms of service")
        }
    }

    @Test
    fun everyLibrary_hasItsVerifiedLicence() {
        val licences = (Licenses.libraries + Licenses.ideas).associate { it.name to it.license }
        assertThat(licences).containsAtLeast(
            "NewPipeExtractor", "GPL-3.0-or-later",
            "AndroidX · Jetpack Compose · Media3", "Apache-2.0",
            "OkHttp", "Apache-2.0",
            "Ktor", "Apache-2.0",
            "Kotlin · kotlinx", "Apache-2.0",
            "Coil", "Apache-2.0",
            "ZXing", "Apache-2.0",
            "Mozilla Rhino", "MPL-2.0",
            "jsoup", "MIT",
            "nanojson", "Apache-2.0",
            "protobuf-javalite", "BSD-3-Clause",
            "IBM Plex Sans Arabic", "OFL-1.1",
            "SmartTube", "MIT",
            "tv-app-lock", "MIT",
        )
        (Licenses.libraries + Licenses.ideas).forEach { entry ->
            assertThat(context.getString(entry.role)).isNotEmpty()
        }
    }
}

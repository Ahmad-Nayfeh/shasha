package com.ahmadnayfeh.shasha.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «حول»: SponsorBlock's attribution word for word, the terms-of-use note, and a licence for every library. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AboutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun sponsorBlocksAttribution_isItsOwnSentenceExactly() {
        assertThat(context.getString(R.string.sponsorblock_attribution_exact))
            .isEqualTo("Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from https://sponsor.ajay.app/")
        assertThat(context.getString(R.string.sponsorblock_attribution)).contains("CC BY-NC-SA 4.0")
    }

    @Test
    fun theTermsNote_saysPersonalUseOnly_andThatItBreaksYouTubesTerms() {
        assertThat(context.getString(R.string.hub_about_terms_title)).isEqualTo("للاستخدام الشخصي فقط")
        assertThat(context.getString(R.string.hub_about_terms_body)).contains("يخالف شروط خدمة يوتيوب")
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

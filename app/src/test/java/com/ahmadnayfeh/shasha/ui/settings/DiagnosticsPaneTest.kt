package com.ahmadnayfeh.shasha.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.search.VoiceAvailability
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * «تشخيص»: one line says which voice recognizer this TV offers (the system's voice screen, used first; the in-app
 * recognizer, the fallback), and the section «معلومات تقنية والتراخيص» holds what «حول» used to show besides the developer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class DiagnosticsPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var state by mutableStateOf(SettingsHubFixtures.state())

    private fun show() {
        compose.setContent {
            ShashaTheme {
                SettingsHubScreen(state, SettingsCategory.DIAGNOSTICS, {}, SettingsHubActions(), remember { ContentFocus() })
            }
        }
        compose.waitForIdle()
    }

    /** Every line of text under the element tagged [tag], as the pane shows it. */
    private fun textsIn(tag: String): List<String> {
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString(" ") }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTvWithBoth_namesTheVoiceScreenFirst_andTheFallback() {
        show()
        val line = textsIn("hub_fact_voice").single()
        assertThat(line).isEqualTo(context.getString(R.string.hub_fact_voice_both, "com.google.android.katniss", "com.google.android.googlequicksearchbox"))
        assertThat(line).contains("com.google.android.katniss")
        assertThat(line).contains("com.google.android.googlequicksearchbox")
        assertThat(line.indexOf("katniss")).isLessThan(line.indexOf("googlequicksearchbox"))
    }

    @Test
    fun aTvWithOnlyOneWayToListen_saysWhichOne() {
        state = state.copy(voice = VoiceAvailability(systemScreen = "com.google.android.katniss", service = null))
        show()
        assertThat(textsIn("hub_fact_voice").single()).isEqualTo(context.getString(R.string.hub_fact_voice_system, "com.google.android.katniss"))

        state = state.copy(voice = VoiceAvailability(systemScreen = null, service = "com.example.recognizer"))
        compose.waitForIdle()
        assertThat(textsIn("hub_fact_voice").single()).isEqualTo(context.getString(R.string.hub_fact_voice_service, "com.example.recognizer"))
    }

    @Test
    fun aTvWithNoWayToListen_saysSo() {
        state = state.copy(voice = VoiceAvailability(systemScreen = null, service = null))
        show()
        assertThat(textsIn("hub_fact_voice").single()).isEqualTo(context.getString(R.string.hub_fact_voice_none))
    }

    @Test
    fun beforeTheTvWasAsked_thereIsNoLine() {
        state = state.copy(voice = null)
        show()
        assertThat(exists("hub_fact_voice")).isFalse()
    }

    @Test
    fun theVersionAndTheExtractor_areInTheTechnicalSection_once() {
        show()
        // «شاشة 0.1.0» and «رقم البناء 1 · مكتبة الاستخراج v0.26.5»: one block, not a second copy among the facts.
        val version = textsIn("diag_version")
        assertThat(version).contains(context.getString(R.string.hub_version, "0.1.0"))
        assertThat(version).contains(context.getString(R.string.hub_build_line, "1", "v0.26.5"))
        assertThat(textsIn("hub_diagnostics_facts").filter { "0.26.5" in it }).isEmpty()
    }

    @Test
    fun theTechnicalSection_hasSponsorBlocksAttributionWordForWord_andEveryLicence() {
        show()
        assertThat(textsIn("diag_sponsorblock")).contains(context.getString(R.string.sponsorblock_attribution_exact))
        for (entry in Licenses.libraries + Licenses.ideas) {
            val row = textsIn("license_${entry.name}")
            assertThat(row).containsAtLeast(entry.name, context.getString(entry.role), entry.license)
        }
    }
}

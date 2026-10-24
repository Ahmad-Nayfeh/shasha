package com.ahmadnayfeh.shasha.ui.screens.updates

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseApk
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The updater screen with the remote only: each state has the few buttons it needs (never a second button to install:
 * the update itself starts by itself), the first of them has the focus, and the words say what is happening.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class UpdatesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calls = mutableListOf<String>()
    private var state by mutableStateOf<UpdateUiState>(UpdateUiState.Checking)

    private val actions = UpdatesActions(
        onBack = { calls += "back" },
        onCheck = { calls += "check" },
        onRetry = { calls += "retry" },
        onCancel = { calls += "cancel" },
    )

    private fun show(first: UpdateUiState) {
        state = first
        compose.setContent { ShashaTheme { UpdatesScreen(state, INSTALLED, actions) } }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ")

    /** Every line of text under the element tagged [tag], as the screen shows it. */
    private fun textsIn(tag: String): String {
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().joinToString(" | ") { it.config[SemanticsProperties.Text].joinToString(" ") }
    }

    private fun buttons() = BUTTONS.filter { exists(it) }

    @Test
    fun eachState_hasOnlyTheButtonsItNeeds_andNeverASecondInstallButton() {
        val table = listOf(
            UpdateUiState.Checking to listOf("updates_back"),
            UpdateUiState.UpToDate to listOf("updates_back", "updates_check"),
            UpdateUiState.Downloading(RELEASE, 7_340_032, 18_874_368) to listOf("updates_cancel"),
            UpdateUiState.Installing(RELEASE) to listOf("updates_back"),
            UpdateUiState.Installing(RELEASE, asked = true) to listOf("updates_back"),
            UpdateUiState.Installing(RELEASE, asked = true, confirmationClosed = true) to listOf("updates_retry", "updates_back"),
            UpdateUiState.Installed(RELEASE) to listOf("updates_back"),
            UpdateUiState.InstallNotAllowed(RELEASE) to listOf("updates_retry", "updates_back"),
            UpdateUiState.Cancelled(RELEASE) to listOf("updates_retry", "updates_back"),
            UpdateUiState.Failed(UpdateErrorKind.NETWORK, release = null) to listOf("updates_retry", "updates_back"),
            UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, RELEASE) to listOf("updates_retry", "updates_back"),
        )
        show(table.first().first)
        for ((next, expected) in table) {
            state = next
            compose.waitForIdle()
            assertWithMessage("buttons of $next").that(buttons()).containsExactlyElementsIn(expected)
        }
    }

    @Test
    fun upToDate_saysSo_withTheInstalledVersion_andNothingToInstall() {
        show(UpdateUiState.UpToDate)
        assertThat(textOf("updates_title")).isEqualTo(context.getString(R.string.updates_up_to_date_title))
        assertThat(textOf("updates_title")).isEqualTo("لديك أحدث إصدار")
        assertThat(textsIn("updates_versions")).contains("1.1.1 (1057)")
        // Only the installed version: there is no newer one to show.
        assertThat(textsIn("updates_versions")).doesNotContain(context.getString(R.string.updates_latest_version))
        compose.onNodeWithTag("updates_back").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("back")
    }

    @Test
    fun downloading_showsTheProgress_andTheCancelButtonHasTheFocus() {
        show(UpdateUiState.Downloading(RELEASE, 7_340_032, 18_874_368))
        assertThat(exists("updates_progress")).isTrue()
        assertThat(textsIn("updates_versions")).contains("1.1.1 (1057)")
        assertThat(textsIn("updates_versions")).contains("1.2.0 (1070)")
        compose.onNodeWithTag("updates_cancel").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("cancel")
    }

    @Test
    fun beforeTheHandOver_theScreenSaysTheAppWillCloseAndOpenAgain() {
        show(UpdateUiState.Installing(RELEASE))
        assertThat(textOf("updates_body")).isEqualTo(context.getString(R.string.updates_installing_body))
        assertThat(textOf("updates_body")).contains("يُفتح من جديد")
        // Where the system asks, its window is on top and the screen tells what to do there.
        state = UpdateUiState.Installing(RELEASE, asked = true)
        compose.waitForIdle()
        assertThat(textOf("updates_body")).isEqualTo(context.getString(R.string.updates_asked_body))
    }

    @Test
    fun anError_putsTheFocusOnRetry_whichRetries() {
        show(UpdateUiState.Failed(UpdateErrorKind.DOWNLOAD_FAILED, RELEASE))
        assertThat(textOf("updates_title")).isEqualTo(context.getString(R.string.updates_error_download_title))
        compose.onNodeWithTag("updates_retry").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(calls).containsExactly("retry")
    }

    @Test
    fun theSitesAnswers_areSaidInArabic() {
        // UpdateFeed: 404 → NOT_FOUND, 403/429 → FORBIDDEN, 5xx or a broken manifest → BAD_ANSWER, no "tv" entry → NO_APK,
        // a short download or another file's SHA-256 → DOWNLOAD_FAILED.
        val expected = mapOf(
            UpdateErrorKind.NOT_FOUND to "لم نجد تحديثاً منشوراً",
            UpdateErrorKind.FORBIDDEN to "الخدمة مشغولة الآن",
            UpdateErrorKind.BAD_ANSWER to "ردّ غير متوقع",
            UpdateErrorKind.NO_APK to "الإصدار الجديد لم يكتمل نشره",
            UpdateErrorKind.DOWNLOAD_FAILED to "انقطع التنزيل",
            UpdateErrorKind.NETWORK to "تعذّر الاتصال بالإنترنت",
        )
        show(UpdateUiState.Checking)
        for ((kind, title) in expected) {
            state = UpdateUiState.Failed(kind, release = null)
            compose.waitForIdle()
            assertThat(textOf("updates_title")).isEqualTo(title)
            assertThat(textOf("updates_body")).isNotEmpty()
        }
    }

    @Test
    fun theFocusMovesToTheNewStatesFirstButton() {
        show(UpdateUiState.Downloading(RELEASE, 0, 18_874_368))
        compose.onNodeWithTag("updates_cancel").assertIsFocused()
        state = UpdateUiState.Cancelled(RELEASE)
        compose.waitForIdle()
        compose.onNodeWithTag("updates_retry").assertIsFocused()
        state = UpdateUiState.Installing(RELEASE, asked = true)
        compose.waitForIdle()
        compose.onNodeWithTag("updates_back").assertIsFocused()
        // The system's window was closed with no answer: retry comes first now.
        state = UpdateUiState.Installing(RELEASE, asked = true, confirmationClosed = true)
        compose.waitForIdle()
        compose.onNodeWithTag("updates_retry").assertIsFocused()
    }

    @Test
    fun aMissingPermission_isSaidInPlainWords() {
        show(UpdateUiState.InstallNotAllowed(RELEASE))
        assertThat(textOf("updates_title")).isEqualTo(context.getString(R.string.updates_not_allowed_title))
        assertThat(exists("updates_command")).isFalse()
        val everyText = compose.onAllNodes(hasAnyAncestor(hasTestTag("updates")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.joinToString(" ") { it.text }
        assertThat(everyText.lowercase()).doesNotContain("adb")
    }

    private companion object {
        val BUTTONS = listOf("updates_back", "updates_check", "updates_retry", "updates_cancel")
        val INSTALLED = InstalledVersion("1.1.1", 1057)
        val RELEASE = ReleaseInfo(
            versionName = "1.2.0",
            versionCode = 1070,
            apk = ReleaseApk("https://downloads.example.test/shasha-tv.apk?v=1070", 18_874_368, "abababababababababababababababababababababababababababababababab"),
        )
    }
}

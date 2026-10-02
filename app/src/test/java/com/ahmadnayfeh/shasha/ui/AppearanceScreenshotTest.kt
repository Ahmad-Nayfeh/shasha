package com.ahmadnayfeh.shasha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.TV_1080P_EN
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.AppTheme
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.screens.guide.ExplainerMissing
import com.ahmadnayfeh.shasha.ui.screens.guide.GuideAssets
import com.ahmadnayfeh.shasha.ui.screens.guide.GuidePageCache
import com.ahmadnayfeh.shasha.ui.screens.guide.GuideScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeState
import com.ahmadnayfeh.shasha.ui.screens.home.HomeSync
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupScreen
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupStep
import com.ahmadnayfeh.shasha.ui.settings.SettingsCategory
import com.ahmadnayfeh.shasha.ui.settings.SettingsHubActions
import com.ahmadnayfeh.shasha.ui.settings.SettingsHubFixtures
import com.ahmadnayfeh.shasha.ui.settings.SettingsHubScreen
import com.ahmadnayfeh.shasha.ui.settings.SettingsHubState
import com.ahmadnayfeh.shasha.ui.theme.Palettes
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.1 part E at 1920x1080: Home, Settings, «حول» and «الوضع الجاد» in English (left to right) and in the light theme,
 * the new «المظهر واللغة» and «الدليل والفيديو» panes, the guide's pages in both languages, the missing explainer.
 * English runs on an English TV configuration (values-en), as MainActivity sets it for the app. Record:
 * ./gradlew :app:recordRoborazziDebug --tests '*AppearanceScreenshotTest*'.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class AppearanceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** The palette is process-wide: the next test class starts dark, as the app does. */
    @After
    fun backToDark() = ShashaColors.use(Palettes.Dark)

    @OptIn(ExperimentalCoilApi::class)
    private fun snap(
        name: String,
        language: AppLanguage = AppLanguage.ARABIC,
        theme: AppTheme = AppTheme.DARK,
        keys: List<Key> = emptyList(),
        waitFor: String? = null,
        content: @Composable () -> Unit,
    ) {
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme(theme = theme, language = language) { content() }
            }
        }
        compose.waitForIdle()
        // The guide's page is decoded off the main thread.
        if (waitFor != null) compose.waitUntil(10_000) { compose.onAllNodesWithTag(waitFor).fetchSemanticsNodes().isNotEmpty() }
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        if (waitFor != null) compose.waitUntil(10_000) { compose.onAllNodesWithTag(waitFor).fetchSemanticsNodes().isNotEmpty() }
        // The settings' pane fades in (180 ms), a scroll animates.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // ---- Home ----------------------------------------------------------------------------------------------------

    @Composable
    private fun Home() {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        val watched = remember { mutableStateOf(UiFixtures.watched) }
        MainShell(Tab.HOME, {}, itemFocus) {
            HomeScreen(
                HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20),
                watched,
                HomeSync(health = SyncHealth(channels = 20, attempted = 20, lastAttemptAtMs = NOW - 5 * 60_000)),
                NOW,
                remember { ContentFocus() },
                "v0.26.5",
                onRefresh = {},
                onPlay = {},
                onImport = {},
                onSearch = {},
                onCheckUpdates = {},
            )
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun homeEnglish() = snap("home_en", language = AppLanguage.ENGLISH) { Home() }

    @Test
    fun homeLight() = snap("home_light", theme = AppTheme.LIGHT) { Home() }

    // ---- Settings ------------------------------------------------------------------------------------------------

    @Composable
    private fun Hub(category: SettingsCategory, state: SettingsHubState = SettingsHubFixtures.state()) {
        var selected by remember { mutableStateOf(category) }
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        MainShell(Tab.SETTINGS, {}, itemFocus) {
            SettingsHubScreen(state, selected, { selected = it }, SettingsHubActions(), remember { ContentFocus() })
        }
    }

    /** Into the pane: the list is at the start, so the pane is LEFT of it in Arabic and RIGHT of it in English. */
    private fun intoPane(language: AppLanguage) = listOf(if (language.isRtl) Key.DirectionLeft else Key.DirectionRight)

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun settingsEnglish() = snap(
        "settings_en",
        language = AppLanguage.ENGLISH,
        keys = intoPane(AppLanguage.ENGLISH),
    ) { Hub(SettingsCategory.APPEARANCE, SettingsHubFixtures.state(behavior = Behavior(appLanguage = AppLanguage.ENGLISH))) }

    @Test
    fun settingsLight() = snap(
        "settings_light",
        theme = AppTheme.LIGHT,
        keys = intoPane(AppLanguage.ARABIC),
    ) { Hub(SettingsCategory.APPEARANCE, SettingsHubFixtures.state(behavior = Behavior(appTheme = AppTheme.LIGHT))) }

    @Test
    fun appearancePane() = snap("settings_hub_appearance", keys = intoPane(AppLanguage.ARABIC)) { Hub(SettingsCategory.APPEARANCE) }

    @Test
    fun guidePane() = snap("settings_hub_guide", keys = intoPane(AppLanguage.ARABIC)) {
        Hub(SettingsCategory.GUIDE, SettingsHubFixtures.state().copy(explainerAvailable = false))
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun aboutEnglish() = snap("settings_hub_about_en", language = AppLanguage.ENGLISH, keys = intoPane(AppLanguage.ENGLISH)) {
        Hub(SettingsCategory.ABOUT)
    }

    @Test
    fun aboutLight() = snap("settings_hub_about_light", theme = AppTheme.LIGHT, keys = intoPane(AppLanguage.ARABIC)) {
        Hub(SettingsCategory.ABOUT)
    }

    /** «Technical information and licenses» in English (it used to be on About): into the pane, DOWN onto the version card. */
    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun diagnosticsEnglish() = snap(
        "settings_hub_diagnostics_en",
        language = AppLanguage.ENGLISH,
        keys = intoPane(AppLanguage.ENGLISH) + List(2) { Key.DirectionDown },
    ) { Hub(SettingsCategory.DIAGNOSTICS) }

    /** DOWN through the developer's apps: their cards and QR codes come into view one by one. */
    @Test
    fun aboutApps() = snap("settings_hub_about_apps", keys = intoPane(AppLanguage.ARABIC) + List(3) { Key.DirectionDown }) {
        Hub(SettingsCategory.ABOUT)
    }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun seriousEnglish() = snap("behavior_serious_en", language = AppLanguage.ENGLISH, keys = intoPane(AppLanguage.ENGLISH)) {
        Hub(SettingsCategory.SERIOUS)
    }

    @Test
    fun seriousLight() = snap("behavior_serious_light", theme = AppTheme.LIGHT, keys = intoPane(AppLanguage.ARABIC)) {
        Hub(SettingsCategory.SERIOUS)
    }

    // ---- The guide and the video ---------------------------------------------------------------------------------

    @Composable
    private fun Guide(language: AppLanguage, page: Int) {
        val context = LocalContext.current
        val paths = remember { GuideAssets.pages(context.assets, language) }
        val width = with(LocalDensity.current) { 640.dp.roundToPx() }
        val pages = remember { GuidePageCache.ofAssets(context.assets, paths, width) }
        GuideScreen(pages, paths.size, onBack = {}, initialPage = page)
    }

    @Test
    fun guideArabic() = snap("guide_ar", waitFor = "guide_page_ready") { Guide(AppLanguage.ARABIC, page = 2) }

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun guideEnglish() = snap("guide_en", language = AppLanguage.ENGLISH, waitFor = "guide_page_ready") { Guide(AppLanguage.ENGLISH, page = 2) }

    /** DOWN scrolls the tall page; the next page comes with LEFT in Arabic. */
    @Test
    fun guideScrolledAndTurned() = snap("guide_ar_turned", keys = listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionDown), waitFor = "guide_page_ready") {
        Guide(AppLanguage.ARABIC, page = 0)
    }

    @Test
    fun explainerMissing() = snap("explainer_missing") { ExplainerMissing(onBack = {}) }

    // ---- The first start's welcome, in English --------------------------------------------------------------------

    @Test
    @Config(sdk = [35], qualifiers = TV_1080P_EN)
    fun welcomeEnglish() = snap("setup_welcome_en", language = AppLanguage.ENGLISH) {
        SetupScreen(
            step = SetupStep.WELCOME,
            busy = false,
            ownerName = "",
            ownerAvatar = "person",
            onStart = {},
            onPin = {},
            onOwner = { _, _ -> },
            onChild = { _, _ -> },
            onSkipChild = {},
        )
    }
}

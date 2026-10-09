package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.BuildConfig
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.container
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/*
 * The real app's screens composed on Robolectric with the real container (a real database and settings; no network is
 * asked: without subscriptions nothing is fetched). They prove that the navigation graph, the four tabs and the screens
 * compose and react to taps without crashing, in English and in Arabic (right to left).
 *
 * What they cannot show: pictures, gestures on the player, the services (see ARCHITECTURE.md, "The phone app").
 */

private fun ComposeContentTestRule.startApp(inspect: @Composable () -> Unit = {}) {
    val container = ApplicationProvider.getApplicationContext<Context>().container
    setContent {
        inspect()
        ShashaRoot(container)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class, qualifiers = "en-$PHONE")
class ShashaRootEnglishTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHomeTabInvitesTheViewerToSearchWhenThereAreNoSubscriptions() {
        var direction: LayoutDirection? = null
        compose.startApp { direction = LocalLayoutDirection.current }

        compose.awaitText("No subscriptions yet")
        compose.onNodeWithText("Search for a channel and subscribe to see its newest videos here.").assertIsDisplayed()
        assertThat(direction).isEqualTo(LayoutDirection.Ltr)
        // The four tabs, in this order from the left.
        for (label in listOf("Home", "Search", "Library", "Settings")) compose.tab(label).assertIsDisplayed()
        val lefts = listOf("Home", "Search", "Library", "Settings").map { compose.tab(it).getBoundsInRoot().left.value }
        assertThat(lefts).isInOrder()
    }

    @Test
    fun theSearchButtonOfTheEmptyHomeOpensTheSearchTab() {
        compose.startApp()
        compose.awaitText("No subscriptions yet")

        compose.onNode(hasText("Search") and hasRole(Role.Button)).performClick()

        compose.awaitText("Search videos and channels")
        compose.onNodeWithText("Search videos and channels").assertIsDisplayed()
    }

    @Test
    fun theLibraryTabLeadsToHistoryWatchLaterAndSubscriptions() {
        compose.startApp()

        compose.tab("Library").performClick()
        compose.onNodeWithText("Downloads").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Watch later").assertIsDisplayed()
        compose.onNodeWithText("Subscriptions").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.awaitText("No history yet")
        compose.onNodeWithText("No history yet").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Downloads").assertIsDisplayed()
    }

    @Test
    fun theSettingsTabShowsEveryGroupWithItsReset() {
        compose.startApp()

        compose.tab("Settings").performClick()

        for (title in listOf("Language", "Theme", "Default quality", "Playback", "Download quality", "SponsorBlock")) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        // One «Reset» per group (the dialog's confirm button reads the same, and is not open here).
        assertThat(compose.onAllNodesWithText("Reset").fetchSemanticsNodes().size).isAtLeast(6)
        compose.onNodeWithText("Reset all settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutShowsTheDeveloperTheOtherAppsAndTheLicences() {
        compose.startApp()
        compose.tab("Settings").performClick()

        compose.onNodeWithText("About").performScrollTo().performClick()

        for (text in listOf("Ahmad Nayfeh", "Made with love for my family", "Shadra", "Silah", "Masarif", "Khatwa")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        // The licences are one closed row at the bottom (nothing technical in view) that opens on a tap.
        val attribution = "Uses SponsorBlock data licensed used under CC BY-NC-SA 4.0 from https://sponsor.ajay.app/."
        compose.onNodeWithText(attribution).assertDoesNotExist()
        compose.onNodeWithText("Licences").performScrollTo().performClick()
        for (text in listOf(attribution, "NewPipeExtractor", "GPL-3.0-or-later")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aTabAlwaysOpensAtItsFirstPage_notAtThePageTheViewerLeftInIt() {
        compose.startApp()
        compose.tab("Library").performClick()
        compose.onNodeWithText("History").performClick()
        compose.awaitText("No history yet")

        compose.tab("Settings").performClick()
        compose.awaitText("Default quality")
        compose.tab("Library").performClick()

        compose.awaitText("Watch later")
        compose.onNodeWithText("No history yet").assertDoesNotExist()
        compose.onNodeWithText("Downloads").assertIsDisplayed()
    }

    @Test
    fun tappingTheTabTheViewerIsInGoesBackToItsFirstPage() {
        compose.startApp()
        compose.tab("Library").performClick()
        compose.onNodeWithText("History").performClick()
        compose.awaitText("No history yet")
        // The page opened from the Library still counts as the Library's.
        compose.tab("Library").assertIsSelected()

        compose.tab("Library").performClick()

        compose.awaitText("Watch later")
        compose.onNodeWithText("No history yet").assertDoesNotExist()
        // Tapped again on its first page: it stays there.
        compose.tab("Library").performClick()
        compose.onNodeWithText("Downloads").assertIsDisplayed()
    }

    @Test
    fun theUpdatePageIsReachableFromSettingsAndNeverNamesWhereUpdatesComeFrom() {
        compose.startApp()
        compose.tab("Settings").performClick()

        compose.onNodeWithText("Check for updates").performScrollTo().performClick()

        // The page has its own back arrow (the settings page does not).
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        // A build without the read-only key (local builds, the test job) says so.
        if (BuildConfig.UPDATE_TOKEN.isBlank()) {
            compose.awaitText("Updates are not available in this version of the app.")
            compose.onNodeWithText("Updates are not available in this version of the app.").assertIsDisplayed()
        }
    }
}

/** The same screens in Arabic: the texts are Arabic and the layout runs right to left. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class, qualifiers = "ar-$PHONE")
class ShashaRootArabicTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theHomeTabIsArabicAndMirrored() {
        var direction: LayoutDirection? = null
        compose.startApp { direction = LocalLayoutDirection.current }

        compose.awaitText("لا اشتراكات بعد")
        compose.onNodeWithText("ابحث عن قناة واشترك فيها لتظهر هنا أحدث فيديوهاتها.").assertIsDisplayed()
        assertThat(direction).isEqualTo(LayoutDirection.Rtl)
        // Right to left: the first tab is the rightmost one.
        val tabs = listOf("الرئيسية", "بحث", "مكتبتي", "الإعدادات")
        for (label in tabs) compose.tab(label).assertIsDisplayed()
        val lefts = tabs.map { compose.tab(it).getBoundsInRoot().left.value }
        assertThat(lefts).isInStrictOrder(Comparator.reverseOrder<Float>())
    }

    @Test
    fun theLibraryAndTheSettingsGroupsAreArabic() {
        compose.startApp()

        compose.tab("مكتبتي").performClick()
        compose.onNodeWithText("التنزيلات").assertIsDisplayed()
        compose.onNodeWithText("السجل").assertIsDisplayed()
        compose.onNodeWithText("المشاهدة لاحقاً").assertIsDisplayed()
        compose.onNodeWithText("الاشتراكات").assertIsDisplayed()

        compose.tab("الإعدادات").performClick()
        for (title in listOf("اللغة", "المظهر", "الجودة الافتراضية", "التشغيل", "جودة التنزيل", "SponsorBlock")) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("إرجاع كل الإعدادات").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAboutPageIsArabicWithTheDevelopersCredit() {
        compose.startApp()
        compose.tab("الإعدادات").performClick()

        compose.onNodeWithText("حول").performScrollTo().performClick()

        for (text in listOf("احمد نايفه", "صُنع بحب لعائلتي", "Shadra", "Silah", "Masarif", "Khatwa")) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }
}

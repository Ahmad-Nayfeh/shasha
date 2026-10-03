package com.ahmadnayfeh.shasha.mobile

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.ui.PHONE
import com.ahmadnayfeh.shasha.mobile.ui.awaitText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real [MainActivity] on Robolectric (a phone that speaks English): the window's own work that no screen test
 * reaches, namely the app's language over the phone's, and what a notification or a link asks the app to open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class, qualifiers = "en-$PHONE")
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val container: AppContainer get() = ApplicationProvider.getApplicationContext<Context>().container

    @Test
    fun theActivityOpensOnTheHomeTabInThePhonesLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitText("No subscriptions yet")
        }
    }

    @Test
    fun theAppsOwnLanguageDecidesWhateverThePhoneSpeaks() {
        runBlocking { container.settings.setLanguage(AppLanguage.ARABIC) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("لا اشتراكات بعد")
            scenario.onActivity { activity ->
                assertThat(activity.resources.configuration.locales[0].language).isEqualTo("ar")
                assertThat(activity.resources.configuration.layoutDirection).isEqualTo(View.LAYOUT_DIRECTION_RTL)
                assertThat(activity.getString(R.string.nav_home)).isEqualTo("الرئيسية")
            }
        }
    }

    @Test
    fun changingTheLanguageInSettingsRestartsTheScreenInTheNewLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitText("No subscriptions yet")

            runBlocking { container.settings.setLanguage(AppLanguage.ARABIC) }

            compose.awaitText("لا اشتراكات بعد")
        }
    }

    @Test
    fun theNotificationOfADownloadOpensTheDownloadsStraightAway() {
        val intent = Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.awaitText("No downloads")
        }
    }
}

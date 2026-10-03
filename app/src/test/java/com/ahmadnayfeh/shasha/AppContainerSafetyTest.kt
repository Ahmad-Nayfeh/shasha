package com.ahmadnayfeh.shasha

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real wiring of the built-in content filter: every screen, the feed, the player and the phone remote ask YouTube through
 * the container's one `videoSource`, so that is where the filter has to be.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppContainerSafetyTest {
    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        container.appScope.cancel()
        container.database.close()
    }

    @Test
    fun theContainersVideoSource_isTheFilteredOne() = runBlocking<Unit> {
        assertThat(container.videoSource).isInstanceOf(SafeVideoSource::class.java)
        // Offline: a search the filter holds back is answered without a request, as a search without results.
        val page = container.videoSource.search("Sexy twerking")
        assertThat(page.items).isEmpty()
        assertThat(page.next).isNull()
        assertThat(container.videoSource.suggestions("porn", "en")).isEmpty()
    }
}

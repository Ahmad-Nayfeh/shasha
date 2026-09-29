package com.ahmadnayfeh.shasha.policy

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.AppContainer
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real wiring: the container's policy parts find each other, the owner is created, the session starts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppContainerPolicyTest {
    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        container.appScope.cancel()
        container.database.close()
    }

    /** The container's coroutines run on the main looper: let it run until [value] has an answer. */
    private fun <T : Any> awaitOnMain(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            value()?.let { return it }
            Thread.sleep(10)
        }
        error("Timed out")
    }

    @Test
    fun firstStart_theOwnerIsCurrent_andTheSessionRuns() {
        assertThat(container.currentProfile.id.value).isEqualTo(ADULT_PROFILE_ID)
        assertThat(container.policy.filter.value).isSameInstanceAs(ContentFilter.NOTHING)

        val owner = awaitOnMain { container.currentProfile.profile.value }
        assertThat(owner.id).isEqualTo(ADULT_PROFILE_ID)
        assertThat(owner.name).isEqualTo("الكبار")
        awaitOnMain { container.policy.filter.value.profile?.takeIf { it.id == ADULT_PROFILE_ID } }
        assertThat(container.policy.filter.value.features!!.search).isTrue()

        container.startPolicy(foreground = MutableStateFlow(false))
        awaitOnMain { container.session.isReady.value.takeIf { it } }
        assertThat(container.session.state.value).isEqualTo(SessionState.Ok(null))
        assertThat(container.policy.lockState.value.playbackLocked).isFalse()
    }
}

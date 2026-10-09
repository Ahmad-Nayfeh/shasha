package com.ahmadnayfeh.shasha.mobile.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.container
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Background playback depends on the media session being the service's from the start: Media3 adds a session to its service by
 * itself only when a MediaController connects, which this app never does, and a service without a session has no media
 * notification, never becomes a foreground service, and is stopped by Android about a minute after the app was left (the video
 * stopped with the screen off). The real service on Robolectric, with the app's one player.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class)
class PlaybackServiceTest {
    @Test
    fun theServiceHoldsTheAppsPlayerInASessionOfItsOwnFromTheStart() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val service = controller.get()
            val sessions = service.sessions

            assertThat(sessions).hasSize(1)
            assertThat(service.isSessionAdded(sessions.single())).isTrue()
            val player = ApplicationProvider.getApplicationContext<Context>().container.player.player
            assertThat(sessions.single().player).isSameInstanceAs(player)
        } finally {
            controller.destroy()
        }
    }
}

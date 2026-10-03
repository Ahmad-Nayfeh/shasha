package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When a player screen that comes back to the front takes the player again (video A, its channel, video B, back, back). */
class WatchViewModelLogicTest {
    private fun state(phase: PlayerPhase, videoId: String?) = PlayerUiState(phase = phase, video = videoId?.let { video(it) })

    @Test
    fun aPlayerThatWasLetGoIsTakenAgain() {
        assertThat(WatchViewModel.playerNeedsReclaiming(state(PlayerPhase.IDLE, null), "a")).isTrue()
        // Stopped by the screen that was in front: nothing is loaded, whatever was shown before.
        assertThat(WatchViewModel.playerNeedsReclaiming(state(PlayerPhase.IDLE, "a"), "a")).isTrue()
    }

    @Test
    fun aPlayerHoldingAnotherVideoIsTakenAgain() {
        for (phase in listOf(PlayerPhase.LOADING, PlayerPhase.READY, PlayerPhase.ENDED, PlayerPhase.ERROR)) {
            assertThat(WatchViewModel.playerNeedsReclaiming(state(phase, "b"), "a")).isTrue()
        }
    }

    @Test
    fun theScreensOwnVideoIsLeftAloneWhateverItIsDoing() {
        for (phase in listOf(PlayerPhase.LOADING, PlayerPhase.READY, PlayerPhase.ENDED, PlayerPhase.ERROR)) {
            assertThat(WatchViewModel.playerNeedsReclaiming(state(phase, "a"), "a")).isFalse()
        }
    }
}

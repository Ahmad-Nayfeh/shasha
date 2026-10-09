package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.watch.pictureInPictureOnLeave
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When leaving the app from the player screen turns the video into a floating window. It used to follow «is playing this very
 * moment», which is false for the instants the player waits for data: leaving then kept only the sound, and whether the window
 * came seemed to be chance.
 */
class PictureInPictureTest {
    private val playing = PlayerUiState(phase = PlayerPhase.READY, video = video("a"), isPlaying = true, playWhenReady = true)

    @Test
    fun aVideoThatPlaysGoesIntoTheWindow_alsoWhileItWaitsForData() {
        assertThat(pictureInPictureOnLeave(playing, setting = true)).isTrue()
        assertThat(pictureInPictureOnLeave(playing.copy(isPlaying = false, isBuffering = true), setting = true)).isTrue()
    }

    @Test
    fun notWhenTheSettingIsOff_theViewerPaused_itEnded_orThereIsNoPicture() {
        assertThat(pictureInPictureOnLeave(playing, setting = false)).isFalse()
        assertThat(pictureInPictureOnLeave(playing.copy(isPlaying = false, playWhenReady = false), setting = true)).isFalse()
        assertThat(pictureInPictureOnLeave(playing.copy(phase = PlayerPhase.ENDED, isPlaying = false), setting = true)).isFalse()
        assertThat(pictureInPictureOnLeave(playing.copy(isAudioOnly = true), setting = true)).isFalse()
        assertThat(pictureInPictureOnLeave(playing.copy(phase = PlayerPhase.ERROR), setting = true)).isFalse()
    }
}

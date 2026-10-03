package com.ahmadnayfeh.shasha.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real controller around a real ExoPlayer (it plays nothing here: a decoder cannot be broken from outside, so the
 * player's listener is handed the error ExoPlayer's video renderer throws): what it does with this TV's decoder failing,
 * and how the safe plan sets up its player.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaybackControllerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val factory by lazy { YouTubeMediaSourceFactory(context, OkHttpClient()) }
    private val errors = mutableListOf<SourceException>()
    private val controllers = mutableListOf<PlaybackController>()

    private fun controller(safe: Boolean = false): PlaybackController =
        PlaybackController(context, factory, TestStreams.details(UiFixtures.videos[0]), safe = safe) { errors += it }.also { controllers += it }

    @After
    fun tearDown() {
        controllers.forEach { it.release() }
    }

    /** What ExoPlayer's video renderer throws when the device's MediaCodec fails on [format]. */
    private fun rendererError(format: Format?, code: Int) = ExoPlaybackException.createForRenderer(
        IllegalStateException("codec died"), "MediaCodecVideoRenderer", 0, format, C.FORMAT_HANDLED, /* mediaPeriodId= */ null,
        /* isRecoverable= */ false, code,
    )

    private fun PlaybackController.playerListener(): Player.Listener =
        PlaybackController::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(this) as Player.Listener

    private val portrait = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.640028").setWidth(1080).setHeight(1920).build()

    @Test
    fun theDecoderFailing_reachesTheCallerAsDeviceDecoder_notAsYouTubeChangingSomething() {
        val controller = controller()
        controller.playerListener().onPlayerError(rendererError(portrait, PlaybackException.ERROR_CODE_DECODING_FAILED))

        val error = errors.single()
        assertThat(error.kind).isEqualTo(SourceErrorKind.DEVICE_DECODER)
        assertThat(error.technicalName).isEqualTo("ERROR_CODE_DECODING_FAILED")
    }

    @Test
    fun aDecoderFailureOfTheSafePlanIsStillTheDecoders() {
        val controller = controller(safe = true)
        controller.playerListener().onPlayerError(rendererError(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertThat(errors.single().kind).isEqualTo(SourceErrorKind.DEVICE_DECODER)
    }

    @Test
    fun otherFailuresAreClassifiedAsBefore() {
        val controller = controller()
        controller.playerListener().onPlayerError(PlaybackException("who knows", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertThat(errors.single().kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
    }

    @Test
    fun theSafePlansPlayerIsHeldToH264AndTheSmallestPicture_theOthersToTheQualitySetting() {
        val normal = controller().player.trackSelectionParameters
        assertThat(normal.preferredVideoMimeTypes).isEmpty()
        assertThat(normal.viewportHeight).isEqualTo(MAX_AUTO_HEIGHT)

        val safe = controller(safe = true).player.trackSelectionParameters
        assertThat(safe.preferredVideoMimeTypes).containsExactly(MimeTypes.VIDEO_H264)
        assertThat(safe.viewportHeight).isEqualTo(480)
        assertThat(safe.viewportOrientationMayChange).isTrue()
    }

    @Test
    fun releasingTwiceIsHarmless() {
        // The failed player is released at once, and the screen releases it again when it leaves.
        val controller = controller()
        controller.release()
        controller.release()
        assertThat(controller.progress()).isEqualTo(PlaybackProgress.Zero)
    }
}

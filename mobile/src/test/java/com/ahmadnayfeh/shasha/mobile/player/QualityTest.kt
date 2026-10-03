package com.ahmadnayfeh.shasha.mobile.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QualityTest {

    private fun video(codecs: String, width: Int, height: Int, fps: Float = 30f, bitrate: Int = height * 2_000) = Format.Builder()
        .setSampleMimeType(if (codecs.startsWith("avc")) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_VP9)
        .setCodecs(codecs).setWidth(width).setHeight(height).setFrameRate(fps).setAverageBitrate(bitrate)
        .build()

    private fun group(vararg formats: Format, support: Int = C.FORMAT_HANDLED) =
        Tracks.Group(TrackGroup(*formats), false, IntArray(formats.size) { support }, BooleanArray(formats.size))

    private fun audioGroup() = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false),
    )

    @Test
    fun oneOptionPerHeightBestFirstAndH264BeatsVp9AtTheSameHeight() {
        val tracks = Tracks(
            listOf(
                audioGroup(),
                group(video("avc1.640028", 1920, 1080), video("avc1.4d401f", 1280, 720), video("avc1.4d401e", 854, 480)),
                group(video("vp09.00.40.08", 1920, 1080), video("vp09.00.31.08", 1280, 720), video("vp09.00.21.08", 640, 360)),
            ),
        )

        val options = PlayerController.qualitiesOf(tracks)

        assertThat(options.map { it.height }).containsExactly(1080, 720, 480, 360).inOrder()
        // The H.264 group (index 1) was chosen where both have the height; VP9 only where it is alone.
        assertThat(options.map { it.groupIndex }).containsExactly(1, 1, 1, 2).inOrder()
        assertThat(options.map { it.trackIndex }).containsExactly(0, 1, 2, 2).inOrder()
    }

    @Test
    fun aVerticalVideoIsLabelledByItsShortSide() {
        val tracks = Tracks(listOf(group(video("avc1.640028", 1080, 1920))))

        assertThat(PlayerController.qualitiesOf(tracks).single().height).isEqualTo(1080)
        assertThat(PlayerController.qualityHeight(video("avc1", 1920, 1080))).isEqualTo(1080)
        assertThat(PlayerController.qualityHeight(Format.Builder().setHeight(480).build())).isEqualTo(480)
    }

    @Test
    fun theHigherFrameRateWinsAtTheSameHeightAndCodec() {
        val tracks = Tracks(listOf(group(video("avc1.64001f", 1280, 720, fps = 30f), video("avc1.64002a", 1280, 720, fps = 60f))))

        val option = PlayerController.qualitiesOf(tracks).single()

        assertThat(option.fps).isEqualTo(60)
        assertThat(option.trackIndex).isEqualTo(1)
    }

    @Test
    fun tracksThePhoneCannotPlayAreNotOffered() {
        val tracks = Tracks(
            listOf(
                group(video("avc1.640028", 1920, 1080), support = C.FORMAT_EXCEEDS_CAPABILITIES),
                group(video("avc1.4d401f", 1280, 720)),
            ),
        )

        assertThat(PlayerController.qualitiesOf(tracks).map { it.height }).containsExactly(720)
    }

    @Test
    fun noVideoTracksNoOptions() {
        assertThat(PlayerController.qualitiesOf(Tracks.EMPTY)).isEmpty()
        assertThat(PlayerController.qualitiesOf(Tracks(listOf(audioGroup())))).isEmpty()
    }

    @Test
    fun theDefaultQualityIsTheBestUpToTheSettingElseTheLowest() {
        val available = listOf(1080, 720, 480, 360, 144)

        assertThat(PlayerController.defaultQualityFor(available, 0)).isNull() // automatic
        assertThat(PlayerController.defaultQualityFor(available, 720)).isEqualTo(720)
        assertThat(PlayerController.defaultQualityFor(available, 1080)).isEqualTo(1080)
        assertThat(PlayerController.defaultQualityFor(available, 900)).isEqualTo(720)
        assertThat(PlayerController.defaultQualityFor(listOf(480, 360), 144)).isEqualTo(360) // nothing that low: the lowest there is
        assertThat(PlayerController.defaultQualityFor(emptyList(), 720)).isNull()
    }

    @Test
    fun speedsRunFromHalfToFourTimesAndAreLabelled() {
        assertThat(PlaybackSpeeds.ALL.first()).isEqualTo(0.5f)
        assertThat(PlaybackSpeeds.ALL.last()).isEqualTo(4f)
        assertThat(PlaybackSpeeds.ALL).isInOrder()
        assertThat(PlaybackSpeeds.ALL).contains(1f)
        assertThat(PlaybackSpeeds.label(1f)).isEqualTo("1×")
        assertThat(PlaybackSpeeds.label(0.5f)).isEqualTo("0.5×")
        assertThat(PlaybackSpeeds.label(1.25f)).isEqualTo("1.25×")
        assertThat(PlaybackSpeeds.label(4f)).isEqualTo("4×")
    }
}

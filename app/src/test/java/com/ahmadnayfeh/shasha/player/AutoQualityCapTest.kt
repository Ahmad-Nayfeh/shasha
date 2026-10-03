package com.ahmadnayfeh.shasha.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Auto" quality through Media3's real track selector, on a 4K TV (3840x2160 px display). Live manifests
 * list up to 2160p and nothing else caps them, so [withAutoQualityCap] must stop at the track that fills a
 * 1080p screen, in either orientation.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-xhdpi")
class AutoQualityCapTest {
    /** The video sizes (width to height) ExoPlayer may adapt between for a group of [sizes]. */
    private fun selectableSizes(sizes: List<Pair<Int, Int>>, capped: Boolean = true, safe: Boolean = false): List<Pair<Int, Int>> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selector = DefaultTrackSelector(context)
        // Like ExoPlayer: parameters set by the app become active when the selector invalidates.
        selector.init({ params -> selector.onParametersActivated(params) }, DefaultBandwidthMeter.Builder(context).build())
        if (capped) selector.setParameters(selector.parameters.withAutoQualityCap())
        if (safe) selector.setParameters(selector.parameters.withSafeQualityCap())

        val formats = sizes.mapIndexed { i, (w, h) ->
            Format.Builder()
                .setId(i.toString())
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(w)
                .setHeight(h)
                .setPeakBitrate(w * h * 2)
                .build()
        }
        val result = selector.selectTracks(
            arrayOf(VideoRenderer),
            TrackGroupArray(TrackGroup(*formats.toTypedArray())),
            MediaSource.MediaPeriodId(Any()),
            Timeline.EMPTY,
        )
        val selection = checkNotNull(result.selections[0]) { "no video selection" }
        return (0 until selection.length()).map { selection.getFormat(it).let { f -> f.width to f.height } }
    }

    @Test
    fun liveLadderUpTo4kIsCappedAt1080p() {
        val sizes = selectableSizes(listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360))
        assertThat(sizes).containsExactly(1920 to 1080, 1280 to 720, 854 to 480, 640 to 360)
    }

    @Test
    fun verticalVideoKeepsIts1080pTrack() {
        val sizes = selectableSizes(listOf(2160 to 3840, 1080 to 1920, 720 to 1280, 480 to 854))
        assertThat(sizes).containsExactly(1080 to 1920, 720 to 1280, 480 to 854)
    }

    @Test
    fun ultraWideVideoStopsAtTheTrackThatFillsTheScreenWidth() {
        val sizes = selectableSizes(listOf(3840 to 1608, 2560 to 1080, 1920 to 804, 1280 to 536))
        assertThat(sizes).containsExactly(1920 to 804, 1280 to 536)
    }

    @Test
    fun withoutTheCapA4kTvWouldAdaptUpTo2160p() {
        val sizes = selectableSizes(listOf(3840 to 2160, 1920 to 1080, 1280 to 720), capped = false)
        assertThat(sizes).contains(3840 to 2160)
    }

    /**
     * The safe retry after a decoder failure also holds the ladders that the factory does not cut (HLS: a video with no
     * adaptive streams, a live stream) to the safe plan's size, in either orientation: the orientation is not known before
     * the tracks are.
     */
    @Test
    fun theSafePlanHoldsAnHlsLadderToItsSmallestPicture_inEitherOrientation() {
        val landscape = selectableSizes(listOf(3840 to 2160, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360), safe = true)
        assertThat(landscape).containsExactly(854 to 480, 640 to 360)
        val vertical = selectableSizes(listOf(2160 to 3840, 1080 to 1920, 720 to 1280, 480 to 854, 360 to 640), safe = true)
        assertThat(vertical).containsExactly(480 to 854, 360 to 640)
    }

    @Test
    fun theSafePlanPrefersH264_andNeverRaisesTheQualitySettingsCap() {
        val params = DefaultTrackSelector.Parameters.DEFAULT.withSafeQualityCap()
        assertThat(params.preferredVideoMimeTypes).containsExactly(MimeTypes.VIDEO_H264)
        assertThat(params.viewportWidth to params.viewportHeight).isEqualTo(854 to 480)
        // A lower setting than the safe plan's stays lower.
        val low = DefaultTrackSelector.Parameters.DEFAULT.withSafeQualityCap(maxQuality = 360)
        assertThat(low.viewportWidth to low.viewportHeight).isEqualTo(640 to 360)
    }

    @Test
    fun capIsNotAHeightLimit() {
        val params = DefaultTrackSelector.Parameters.DEFAULT.withAutoQualityCap()
        assertThat(params.maxVideoHeight).isEqualTo(Int.MAX_VALUE)
        assertThat(params.viewportOrientationMayChange).isTrue()
        assertThat(params.viewportWidth to params.viewportHeight).isEqualTo(1920 to MAX_AUTO_HEIGHT)
    }

    /** A video decoder that plays everything and switches quality seamlessly. */
    private object VideoRenderer : RendererCapabilities {
        override fun getName() = "FakeVideoRenderer"

        override fun getTrackType() = C.TRACK_TYPE_VIDEO

        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )

        override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }
}

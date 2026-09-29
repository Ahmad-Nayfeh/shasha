package com.ahmadnayfeh.shasha.ui.settings

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
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.player.qualityOptions
import com.ahmadnayfeh.shasha.player.viewportWidth
import com.ahmadnayfeh.shasha.player.withAutoQualityCap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §7 «الجودة الافتراضية» reaches the player: "Auto" stops at the chosen quality through Media3's real track
 * selector on a 4K TV, and the quality menu offers nothing above it (live manifests list up to 2160p).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-xhdpi")
class QualityCapTest {
    private val ladder = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480, 640 to 360)

    private fun selectable(cap: Int, sizes: List<Pair<Int, Int>> = ladder): List<Int> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selector = DefaultTrackSelector(context)
        selector.init({ params -> selector.onParametersActivated(params) }, DefaultBandwidthMeter.Builder(context).build())
        selector.setParameters(selector.parameters.withAutoQualityCap(cap))
        val formats = sizes.mapIndexed { i, (w, h) ->
            Format.Builder().setId(i.toString()).setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(w).setHeight(h).setPeakBitrate(w * h * 2).build()
        }
        val result = selector.selectTracks(
            arrayOf(VideoRenderer),
            TrackGroupArray(TrackGroup(*formats.toTypedArray())),
            MediaSource.MediaPeriodId(Any()),
            Timeline.EMPTY,
        )
        val selection = checkNotNull(result.selections[0]) { "no video selection" }
        return (0 until selection.length()).map { minOf(selection.getFormat(it).width, selection.getFormat(it).height) }
    }

    @Test
    fun everyOfferedCap_isWhereAutoStops() {
        assertThat(selectable(2160)).containsExactly(2160, 1440, 1080, 720, 480, 360)
        assertThat(selectable(1440)).containsExactly(1440, 1080, 720, 480, 360)
        assertThat(selectable(1080)).containsExactly(1080, 720, 480, 360)
        assertThat(selectable(720)).containsExactly(720, 480, 360)
        assertThat(selectable(480)).containsExactly(480, 360)
    }

    @Test
    fun aVerticalVideo_keepsItsQualityUnderTheCap() {
        val vertical = listOf(2160 to 3840, 1080 to 1920, 720 to 1280, 480 to 854)
        assertThat(selectable(720, vertical)).containsExactly(720, 480)
        assertThat(selectable(2160, vertical)).containsExactly(2160, 1080, 720, 480)
    }

    @Test
    fun theViewport_isTheQualitys16to9Frame() {
        assertThat(PlaybackSettings.QUALITY_CAPS.map(::viewportWidth)).containsExactly(3840, 2560, 1920, 1280, 854).inOrder()
    }

    @Test
    fun theMenuOffersFixedQualities_onlyUpToTheCap() {
        val live = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        assertThat(qualityOptions(live)).containsExactly(1080, 720, 480, 360).inOrder()
        assertThat(qualityOptions(live, cap = 2160)).containsExactly(2160, 1440, 1080, 720, 480, 360).inOrder()
        assertThat(qualityOptions(live, cap = 720)).containsExactly(720, 480, 360).inOrder()
        assertThat(qualityOptions(listOf(1080, 720), cap = 1440)).containsExactly(1080, 720).inOrder()
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

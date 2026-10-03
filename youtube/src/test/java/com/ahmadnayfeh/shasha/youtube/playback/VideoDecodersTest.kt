package com.ahmadnayfeh.shasha.youtube.playback

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder

/**
 * The device check against decoders as Android lists them: real Media3 [MediaCodecInfo]s over real Android video capabilities
 * (Robolectric builds those from the limits a decoder declares), so the leniency the check removes is Media3's own.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class VideoDecodersTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * An H.264 decoder (every profile YouTube uses) that declares pictures up to [maxWidth]x[maxHeight] (a TV SoC's "1080p" decoder lists 1920x1088: it
     * does not list the rotated 1088x1920). [alignment]: the block alignment it declares.
     */
    private fun decoder(
        name: String,
        maxWidth: Int,
        maxHeight: Int,
        hardware: Boolean = true,
        alignment: String? = null,
    ): MediaCodecInfo {
        val limits = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MimeTypes.VIDEO_H264)
            setInteger("width", 64)
            setInteger("height", 64)
            setInteger("max-width", maxWidth)
            setInteger("max-height", maxHeight)
            alignment?.let { setString("alignment", it) }
        }
        val capabilities: CodecCapabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(limits)
            .setProfileLevels(
                // What YouTube's H.264 comes in: Baseline, Main and High.
                intArrayOf(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileHigh)
                    .map { p -> CodecProfileLevel().apply { profile = p; level = CodecProfileLevel.AVCLevel52 } }
                    .toTypedArray(),
            )
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatSurface))
            .build()
        return MediaCodecInfo.newInstance(
            name, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264, capabilities,
            /* hardwareAccelerated= */ hardware, /* softwareOnly= */ !hardware, /* vendor= */ hardware,
            /* forceDisableAdaptive= */ false, /* forceSecure= */ false,
        )
    }

    private fun check(vararg decoders: MediaCodecInfo) = MediaCodecVideoDecoderCheck(context) { decoders.toList() }

    private fun h264(width: Int, height: Int, fps: Int = 30) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs(HIGH).setWidth(width).setHeight(height).setFrameRate(fps.toFloat()).build()

    /** The decoder of the owner's TCL (MediaTek): 1080p class, it lists 1920x1088 and not the rotated size. */
    private val tcl get() = decoder("OMX.MTK.VIDEO.DECODER.AVC", maxWidth = 1920, maxHeight = 1088)

    @Test
    fun media3AcceptsAVerticalPictureWhenOnlyTheRotatedSizeIsListed_theCheckDoesNot() {
        // The premise: Media3 says yes to 1080x1920 on this decoder, because 1920x1080 is listed ("rotated vertical resolution")...
        assertThat(tcl.isFormatSupported(context, h264(1080, 1920))).isTrue()
        assertThat(tcl.capabilities!!.videoCapabilities!!.isSizeSupported(1080, 1920)).isFalse()
        // ...and that is the picture which then failed to decode on the TV: not decodable here.
        assertThat(check(tcl).check(HIGH, 1080, 1920, 30)).isEqualTo(DecoderKind.NONE)
        assertThat(check(tcl).check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.NONE)
    }

    @Test
    fun aVerticalPictureTheDecoderListsAsItIsIsDecodable() {
        assertThat(check(tcl).check(HIGH, 480, 854, 30)).isEqualTo(DecoderKind.HARDWARE)
        assertThat(check(tcl).check(HIGH, 360, 640, 30)).isEqualTo(DecoderKind.HARDWARE)
        // A decoder that lists tall pictures takes the big one.
        assertThat(check(decoder("c2.vendor.avc.decoder", 4096, 4096)).check(HIGH, 1080, 1920, 30)).isEqualTo(DecoderKind.HARDWARE)
    }

    /** The owner's report end to end: a Short on the TCL, planned with the device check and the TV's vertical cap. */
    @Test
    fun aShortOnTheOwnersTv_isPlannedOnlyWithPicturesItsDecoderListsAsTheyAre() {
        val planner = PlaybackPlanner(check(tcl))
        val ladder = PlaybackFixtures.portraitLadder()
        val tv = PlanLimits(maxPortraitShortSide = 720)

        // Before the strict check, "1080p" and "720p" passed for the H.264 decoder (their rotated sizes are listed) and failed on screen.
        val planned = planner.selectVideos(ladder, maxHeight = 1080, limits = tv)
        assertThat(planned.map { it.width to it.height }).containsExactly(480 to 854, 360 to 640, 144 to 256).inOrder()
        assertThat(planned.map { it.decoder }.distinct()).containsExactly(DecoderKind.HARDWARE)
        // A landscape video on the same TV keeps its 1080p.
        assertThat(planner.selectVideos(PlaybackFixtures.standardLadder(), 1080, tv).map { it.width to it.height }).contains(1920 to 1080)
    }

    @Test
    fun landscapePicturesAreJudgedAsBefore() {
        assertThat(check(tcl).check(HIGH, 1920, 1080, 30)).isEqualTo(DecoderKind.HARDWARE)
        assertThat(check(tcl).check(HIGH, 3840, 2160, 30)).isEqualTo(DecoderKind.NONE)
        assertThat(check(decoder("software", 1920, 1088, hardware = false)).check(HIGH, 1280, 720, 30)).isEqualTo(DecoderKind.SOFTWARE)
    }

    @Test
    fun theNextDecoderIsAskedWhenTheFirstCannotTakeThePictureExactly() {
        val software = decoder("OMX.google.h264.decoder", 4096, 4096, hardware = false)
        // The hardware decoder only lists the rotated size: the software one, which lists the picture, is the answer.
        assertThat(check(tcl, software).check(HIGH, 1080, 1920, 30)).isEqualTo(DecoderKind.SOFTWARE)
        // One that takes it exactly, in hardware, wins over the software one.
        assertThat(check(software, decoder("c2.vendor.avc.decoder", 4096, 4096)).check(HIGH, 1080, 1920, 30)).isEqualTo(DecoderKind.HARDWARE)
    }

    @Test
    fun blockAlignmentNeverFailsAPicture() {
        // 1080 is not a multiple of 16: it is coded 1088 wide. Media3 aligns up before it asks ("don't ever fail due to
        // alignment", ExoPlayer #6551) and so does the check, or a decoder aligned to 16 would refuse every vertical 1080.
        val aligned = decoder("aligned", 4096, 4096, alignment = "16x16")
        assertThat(aligned.capabilities!!.videoCapabilities!!.isSizeSupported(1080, 1920)).isFalse()
        assertThat(aligned.alignVideoSize(1080, 1920)!!.x).isEqualTo(1088)
        assertThat(check(aligned).check(HIGH, 1080, 1920, 30)).isEqualTo(DecoderKind.HARDWARE)
        assertThat(check(aligned).check(HIGH, 480, 854, 30)).isEqualTo(DecoderKind.HARDWARE)
    }

    @Test
    fun aDecoderWithoutVideoCapabilitiesTakesNoVerticalPicture() {
        val noCaps = MediaCodecInfo.newInstance("none", MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264, null, true, false, true, false, false)
        assertThat(noCaps.takesExactSize(1080, 1920, 30)).isFalse()
    }

    @Test
    fun theRuleIsTheDecodersOwnAnswerForThePictureNotTurned() {
        val d = tcl
        assertThat(d.takesExactSize(1920, 1080, 30)).isTrue()
        assertThat(d.takesExactSize(1080, 1920, 30)).isFalse()
        assertThat(d.takesExactSize(1080, 1920, /* unknown frame rate */ -1)).isFalse()
        assertThat(d.takesExactSize(854, 480, -1)).isTrue()
        assertThat(d.takesExactSize(480, 854, -1)).isTrue()
    }

    @Test
    fun aVerdictThatFailedOnScreenIsNotTrustedAgain() {
        var decoders = listOf(decoder("c2.vendor.avc.decoder", 4096, 4096))
        val check = MediaCodecVideoDecoderCheck(context) { decoders }
        assertThat(check.check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.HARDWARE)

        // The device list changes under us (or it was wrong all along); the cached answer stands until the failure is reported.
        decoders = emptyList()
        assertThat(check.check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.HARDWARE)
        check.forget(HIGH, 720, 1280)
        assertThat(check.check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.NONE)
    }

    @Test
    fun forgettingAPictureLeavesTheOtherAnswersCached() {
        var decoders = listOf(decoder("c2.vendor.avc.decoder", 4096, 4096))
        val check = MediaCodecVideoDecoderCheck(context) { decoders }
        assertThat(check.check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.HARDWARE)
        assertThat(check.check(HIGH, 1920, 1080, 30)).isEqualTo(DecoderKind.HARDWARE)

        decoders = emptyList()
        check.forget(HIGH, 720, 1280)
        assertThat(check.check(HIGH, 1920, 1080, 30)).isEqualTo(DecoderKind.HARDWARE) // not the failed picture: still cached
        assertThat(check.check(HIGH, 720, 1280, 30)).isEqualTo(DecoderKind.NONE)
    }

    private companion object {
        const val HIGH = "avc1.640028"
    }
}

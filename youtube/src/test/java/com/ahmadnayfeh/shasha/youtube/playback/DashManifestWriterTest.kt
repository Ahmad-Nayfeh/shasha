package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The generated MPD must be what Media3's own DASH parser understands, with the exact byte ranges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DashManifestWriterTest {

    private val url1080 = "https://rr1---sn-a.googlevideo.com/videoplayback?expire=1&itag=137&c=ANDROID&sig=a%3Db&x=<y>"

    private val manifest: DashManifest by lazy {
        val xml = DashManifestWriter.write(
            durationMs = 212_091,
            adaptationSets = listOf(
                DashAdaptationSet(
                    DashContentType.VIDEO, "video/mp4",
                    listOf(
                        DashRepresentation("v137", url1080, "avc1.640028", 4_400_000, 0, 740, 741, 1296, 1920, 1080, 30),
                        DashRepresentation("v136", "https://rr1---sn-a.googlevideo.com/videoplayback?itag=136", "avc1.4d401f", 2_300_000, 0, 739, 740, 1295, 1280, 720, 30),
                    ),
                ),
                DashAdaptationSet(
                    DashContentType.VIDEO, "video/webm",
                    listOf(DashRepresentation("v248", "https://rr1---sn-a.googlevideo.com/videoplayback?itag=248", "vp9", 2_600_000, 0, 219, 220, 976, 1920, 1080, 25)),
                ),
                DashAdaptationSet(
                    DashContentType.AUDIO, "audio/mp4",
                    listOf(DashRepresentation("a140", "https://rr1---sn-a.googlevideo.com/videoplayback?itag=140", "mp4a.40.2", 130_000, 0, 631, 632, 923, sampleRate = 44_100, channels = 2)),
                    language = "en",
                ),
            ),
        )
        DashManifestParser().parse(Uri.parse("shasha-manifest://test"), xml.byteInputStream())
    }

    @Test
    fun staticManifestWithDuration() {
        assertThat(manifest.dynamic).isFalse()
        assertThat(manifest.periodCount).isEqualTo(1)
        assertThat(manifest.durationMs).isEqualTo(212_091)
        assertThat(manifest.getPeriodDurationMs(0)).isEqualTo(212_091)
    }

    @Test
    fun oneAdaptationSetPerContainerAndCodec() {
        val sets = manifest.getPeriod(0).adaptationSets
        assertThat(sets.map { it.type }).containsExactly(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO).inOrder()
        assertThat(sets.map { it.representations.size }).containsExactly(2, 1, 1).inOrder()
    }

    @Test
    fun videoRepresentationsKeepFormatAndRanges() {
        val r = manifest.getPeriod(0).adaptationSets[0].representations[0]
        assertThat(r.format.id).isEqualTo("v137")
        assertThat(r.format.codecs).isEqualTo("avc1.640028")
        assertThat(r.format.containerMimeType).isEqualTo("video/mp4")
        assertThat(r.format.width).isEqualTo(1920)
        assertThat(r.format.height).isEqualTo(1080)
        assertThat(r.format.frameRate).isEqualTo(30f)
        assertThat(r.format.bitrate).isEqualTo(4_400_000)
        assertRanges(r, initStart = 0, initEnd = 740, indexStart = 741, indexEnd = 1296)
        // XML escaping must round-trip: googlevideo rejects a URL whose signature changed.
        assertThat(r.baseUrls.single().url).isEqualTo(url1080)

        val vp9 = manifest.getPeriod(0).adaptationSets[1].representations.single()
        assertThat(vp9.format.containerMimeType).isEqualTo("video/webm")
        assertThat(vp9.format.codecs).isEqualTo("vp9")
        assertRanges(vp9, initStart = 0, initEnd = 219, indexStart = 220, indexEnd = 976)
    }

    @Test
    fun audioRepresentationHasChannelsSampleRateAndLanguage() {
        val set = manifest.getPeriod(0).adaptationSets[2]
        val r = set.representations.single()
        assertThat(r.format.codecs).isEqualTo("mp4a.40.2")
        assertThat(r.format.sampleRate).isEqualTo(44_100)
        assertThat(r.format.channelCount).isEqualTo(2)
        assertThat(r.format.language).isEqualTo("en")
        assertThat(r.format.roleFlags and C.ROLE_FLAG_MAIN).isEqualTo(C.ROLE_FLAG_MAIN)
        assertRanges(r, initStart = 0, initEnd = 631, indexStart = 632, indexEnd = 923)
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        assertThat(DashManifestWriter.escape("""a&b<c>"d'""")).isEqualTo("a&amp;b&lt;c&gt;&quot;d&apos;")
        assertThat(DashManifestWriter.escape("plain")).isEqualTo("plain")
    }

    private fun assertRanges(r: Representation, initStart: Long, initEnd: Long, indexStart: Long, indexEnd: Long) {
        val init = r.initializationUri!!
        assertThat(init.start).isEqualTo(initStart)
        assertThat(init.length).isEqualTo(initEnd - initStart + 1)
        val index = r.indexUri!!
        assertThat(index.start).isEqualTo(indexStart)
        assertThat(index.length).isEqualTo(indexEnd - indexStart + 1)
    }
}

/** One Robolectric SDK for every playback test: its android-all jar is downloaded once. */
internal const val ROBOLECTRIC_SDK = 35

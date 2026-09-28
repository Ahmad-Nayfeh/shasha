package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayOutputStream
import java.time.Duration

/**
 * Talks to the real YouTube. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest -Dyoutube.realNetwork=true
 * Every test prints one "REAL-RESULT" line.
 *
 * Robolectric has no media decoders, so the decoder check says "hardware" for everything: this
 * verifies the manifest and the googlevideo request shape, not the TV's codecs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class RealNetworkPlaybackTest {

    private val client = OkHttpClient()
    private val statuses = mutableListOf<Int>()
    private val allHardware = VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }
    private lateinit var details: StreamDetails

    @Before
    fun setUp() = runBlocking {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        details = NewPipeVideoSource(client).stream("dQw4w9WgXcQ")
    }

    @Test
    fun generatedMpdServesInitIndexAndFirstSegmentOfBestVideo() {
        val plan = PlaybackPlanner(allHardware).plan(details.playback.info, maxHeight = 1080, subtitleLanguage = null)
        val dash = plan.parts.first() as MediaPart.Dash
        assertThat(dash.kind).isEqualTo("generated")
        val manifest = DashManifestParser().parse(Uri.parse("shasha-manifest://real"), dash.manifest.byteInputStream())
        val sets = manifest.getPeriod(0).adaptationSets
        val videos = sets.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { it.representations }
        val audios = sets.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { it.representations }
        val best = videos.maxWith(compareBy<Representation> { it.format.height }.thenBy { it.format.bitrate })

        val dataSource = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.DASH).createDataSource()
        val init = fetch(dataSource, best, best.initializationUri!!.start, best.initializationUri!!.length)
        val index = fetch(dataSource, best, best.indexUri!!.start, best.indexUri!!.length)
        val isMp4 = best.format.containerMimeType == "video/mp4"
        val firstSegment = if (isMp4) {
            val (offset, size) = firstSegmentOf(index, best.indexUri!!.start + best.indexUri!!.length)
            fetch(dataSource, best, offset, minOf(size, 64L * 1024))
        } else {
            null
        }
        val audio = audios.first()
        val audioInit = fetch(dataSource, audio, audio.initializationUri!!.start, audio.initializationUri!!.length)

        println(
            "REAL-RESULT | playback dQw4w9WgXcQ MPD | OK: ${videos.size} video reps (${videos.map { it.format.height }.distinct()}), " +
                "${audios.size} audio reps; best ${best.format.id} ${best.format.codecs} ${best.format.height}p: " +
                "init ${init.size} B, index ${index.size} B, " +
                "first segment ${firstSegment?.size ?: "-"} B; audio ${audio.format.id} init ${audioInit.size} B; HTTP statuses $statuses",
        )
        assertThat(best.format.height).isEqualTo(1080)
        assertThat(videos.map { it.format.height }.max()).isAtMost(1080)
        assertThat(init.size.toLong()).isEqualTo(best.initializationUri!!.length)
        assertThat(index.size.toLong()).isEqualTo(best.indexUri!!.length)
        if (isMp4) {
            assertThat(boxType(init)).isEqualTo("ftyp")
            assertThat(boxType(index)).isEqualTo("sidx")
            assertThat(boxType(firstSegment!!)).isAnyOf("moof", "styp")
        } else {
            assertThat(init.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        }
        assertThat(audioInit.size.toLong()).isEqualTo(audio.initializationUri!!.length)
    }

    @Test
    fun factoryMediaSourceLoadsGeneratedManifestAndSubtitles() {
        val language = details.subtitles.firstOrNull { !it.isAutoGenerated }?.languageCode ?: details.subtitles.firstOrNull()?.languageCode
        val source = YouTubeMediaSourceFactory(client, allHardware).create(details, maxHeight = 1080, subtitleLanguage = language)
        var timeline: Timeline? = null
        val bandwidthMeter = DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build()
        source.prepareSource(
            { _, t -> if (!t.isEmpty && t.getWindow(0, Timeline.Window()).durationUs != C.TIME_UNSET) timeline = t },
            PlayerId.UNSET,
            bandwidthMeter,
        )
        val deadline = System.currentTimeMillis() + 20_000
        while (timeline == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            source.maybeThrowSourceInfoRefreshError()
            Thread.sleep(20)
        }
        val window = timeline?.getWindow(0, Timeline.Window())
        println("REAL-RESULT | playback dQw4w9WgXcQ source | OK: ${source.javaClass.simpleName}, window ${window?.durationMs} ms, subtitles=$language, mediaId=${source.mediaItem.mediaId}")
        assertWithMessage("timeline").that(window).isNotNull()
        assertThat(window!!.durationMs).isIn(com.google.common.collect.Range.closed(details.video.durationSeconds!! * 1000 - 2000, details.video.durationSeconds!! * 1000 + 2000))
        assertThat(source.mediaItem.mediaId).isEqualTo("dQw4w9WgXcQ")
        source.releaseSource { _, _ -> }
    }

    @Test
    fun muxedProgressiveLastResortIsServed() {
        val muxed = details.playback.info.videoStreams.first { it.isUrl && !it.isVideoOnly() }
        val dataSource = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.PROGRESSIVE).createDataSource()
        val spec = DataSpec.Builder().setUri(muxed.content).setPosition(0).setLength(64 * 1024).build()
        val bytes = readAll(dataSource, spec)
        println("REAL-RESULT | playback dQw4w9WgXcQ muxed | OK: itag ${muxed.id} ${muxed.getResolution()}, ${bytes.size} B, HTTP $statuses")
        assertThat(statuses.single()).isAnyOf(200, 206)
        assertThat(bytes.size).isEqualTo(64 * 1024)
        assertThat(boxType(bytes)).isEqualTo("ftyp")
    }

    /**
     * The SPEC's phase-1 question: does a "made for kids" video play? NPE v0.26.5 extracts only ANDROID's
     * muxed itag 18 (360p) for it, so the app shows LOW_QUALITY_ONLY. This also asks googlevideo for the
     * first bytes of that file, as the player would (POST, range, ANDROID User-Agent) and as a plain GET,
     * to tell "360p only" (bytes arrive) from "does not work" (refused).
     */
    @Test
    fun kidsVideoMuxedFileIsRequestedLikeThePlayerWould() = runBlocking {
        val id = "it3OtbTxQk0"
        val verdict = try {
            NewPipeVideoSource(client).stream(id)
            null
        } catch (e: SourceException) {
            e
        }
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val muxed = info.videoStreams.filter { it.isUrl && !it.isVideoOnly() }
        val answers = muxed.map { stream ->
            val asPlayer = firstBytesStatus(GoogleVideoQuirks.PROGRESSIVE, stream.content)
            val plainGet = firstBytesStatus(GoogleVideoQuirks.LIVE, stream.content)
            "itag ${stream.id} ${stream.getResolution()} c=${Uri.parse(stream.content).getQueryParameter("c")}: " +
                "POST+range $asPlayer, GET $plainGet"
        }
        println(
            "REAL-RESULT | kids $id bytes | app: ${verdict?.let { "${it.kind} ${it.technicalName}" } ?: "PLAYABLE"}; " +
                "${info.videoOnlyStreams.size} video-only, ${info.audioStreams.size} audio, muxed: $answers",
        )
        if (verdict == null) {
            assertThat(info.videoOnlyStreams).isNotEmpty() // a real ladder came back: the app plays it
        } else {
            assertThat(verdict.kind).isAnyOf(SourceErrorKind.LOW_QUALITY_ONLY, SourceErrorKind.BLOCKED_403)
        }
    }

    /** The VOD fallback when adaptive video is unusable: YouTube's HLS manifest must list HD with sound. */
    @Test
    fun vodHlsManifestLoads() {
        val hlsUrl = details.playback.info.hlsUrl
        assumeTrue("no HLS manifest for this VOD", !hlsUrl.isNullOrBlank())
        val dataSource = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.LIVE).createDataSource()
        val master = HlsPlaylistParser().parse(Uri.parse(hlsUrl), readAll(dataSource, DataSpec(Uri.parse(hlsUrl))).inputStream())
            as HlsMultivariantPlaylist
        val heights = master.variants.map { minOf(it.format.width, it.format.height) }.filter { it > 0 }.distinct().sorted()
        val variant = master.variants.filter { minOf(it.format.width, it.format.height) in 1..1080 }
            .maxByOrNull { it.format.height }?.url ?: master.variants.first().url
        val media = HlsPlaylistParser().parse(variant, readAll(dataSource, DataSpec(variant)).inputStream()) as HlsMediaPlaylist
        val first = media.segments.first()
        val segmentUri = Uri.parse(UriUtil.resolve(variant.toString(), first.url))
        val segmentStatus = firstBytesStatus(GoogleVideoQuirks.LIVE, segmentUri.toString(), position = first.byteRangeOffset)
        println(
            "REAL-RESULT | playback dQw4w9WgXcQ VOD HLS | ${master.variants.size} variants, qualities $heights, " +
                "${master.audios.size} audio renditions, muxed audio=${master.variants.any { it.format.codecs?.contains("mp4a") == true }}; " +
                "variant ${media.segments.size} segments; first segment $segmentStatus",
        )
        assertThat(master.variants).isNotEmpty()
        assertThat(heights.max()).isAtLeast(720)
        assertThat(media.segments).isNotEmpty()
    }

    /**
     * Live: the playlists must load. Segments are only reported: from this build machine every live
     * segment request answered 403 (HLS and DASH, GET or POST, any User-Agent), while VOD works from
     * the same address; this has to be checked on the TV.
     */
    @Test
    fun liveHlsPlaylistsLoad() = runBlocking {
        val source = NewPipeVideoSource(client)
        val found = source.search("live", com.ahmadnayfeh.shasha.youtube.SearchFilter.VIDEOS).items
            .filterIsInstance<com.ahmadnayfeh.shasha.youtube.VideoItem>().filter { it.isLive }.map { it.videoId }
        val live = (found.take(3) + listOf("nI725iVsyoQ", "j9epFget1W8")).distinct().firstNotNullOf { id ->
            runCatching { source.stream(id) }.getOrNull()?.takeIf { it.video.isLive && !it.playback.info.hlsUrl.isNullOrBlank() }
        }
        val plan = PlaybackPlanner(allHardware).plan(live.playback.info, 1080, null)
        val hls = plan.parts.single() as MediaPart.Hls
        val dataSource = YouTubeHttpDataSource.Factory(client, GoogleVideoQuirks.LIVE).createDataSource()
        val master = HlsPlaylistParser().parse(Uri.parse(hls.url), readAll(dataSource, DataSpec(Uri.parse(hls.url))).inputStream())
            as HlsMultivariantPlaylist
        val variant = master.variants.minBy { it.format.bitrate }.url
        val media = HlsPlaylistParser().parse(variant, readAll(dataSource, DataSpec(variant)).inputStream()) as HlsMediaPlaylist
        val segment = Uri.parse(UriUtil.resolve(variant.toString(), media.segments.last().url))
        val segmentStatus = try {
            readAll(dataSource, DataSpec(segment))
            "HTTP ${statuses.last()}"
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            "HTTP ${e.responseCode} (" + factoryClassification(e) + ")"
        }
        println(
            "REAL-RESULT | playback live ${live.video.videoId} | playlists OK (${master.variants.size} variants, " +
                "${media.segments.size} segments); newest segment: $segmentStatus",
        )
        assertThat(hls.live).isTrue()
        assertThat(master.variants).isNotEmpty()
        assertThat(media.segments).isNotEmpty()
    }

    private fun factoryClassification(e: Throwable) = PlaybackErrorClassifier.classify(e).let { "${it.kind} ${it.technicalName}" }

    /** "HTTP 206 (65536 B)" or "HTTP 403": the answer to a request for the first 64 KB. */
    private fun firstBytesStatus(quirks: GoogleVideoQuirks, url: String, position: Long = 0): String {
        val dataSource = YouTubeHttpDataSource.Factory(client, quirks).createDataSource()
        val spec = DataSpec.Builder().setUri(url).setPosition(position).setLength(64L * 1024).build()
        return try {
            val bytes = readAll(dataSource, spec)
            "HTTP ${statuses.last()} (${bytes.size} B)"
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            "HTTP ${e.responseCode}"
        }
    }

    private fun readAll(dataSource: HttpDataSource, spec: DataSpec): ByteArray {
        dataSource.open(spec)
        try {
            statuses += dataSource.responseCode
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = dataSource.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            dataSource.close()
        }
    }

    private fun fetch(dataSource: HttpDataSource, r: Representation, position: Long, length: Long): ByteArray {
        val spec = DataSpec.Builder().setUri(r.baseUrls.first().url).setPosition(position).setLength(length).build()
        val opened = dataSource.open(spec)
        try {
            statuses += dataSource.responseCode
            assertWithMessage("HTTP status").that(dataSource.responseCode).isAnyOf(200, 206)
            assertThat(opened).isEqualTo(length)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = dataSource.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            dataSource.close()
        }
    }

    private fun boxType(bytes: ByteArray) = String(bytes, 4, 4, Charsets.US_ASCII)

    /** Offset and size of the first media segment, from an ISO BMFF "sidx" box that ends at [indexEnd]. */
    private fun firstSegmentOf(sidx: ByteArray, indexEnd: Long): Pair<Long, Long> {
        fun u32(at: Int) = ((sidx[at].toLong() and 0xFF) shl 24) or ((sidx[at + 1].toLong() and 0xFF) shl 16) or
            ((sidx[at + 2].toLong() and 0xFF) shl 8) or (sidx[at + 3].toLong() and 0xFF)
        val version = sidx[8].toInt()
        // size(4) type(4) version+flags(4) reference_ID(4) timescale(4)
        var at = 20
        at += if (version == 0) 4 else 8 // earliest_presentation_time
        val firstOffset = if (version == 0) u32(at) else (u32(at) shl 32) or u32(at + 4)
        at += if (version == 0) 4 else 8
        at += 4 // reserved(2) + reference_count(2)
        val referencedSize = u32(at) and 0x7FFFFFFF
        return (indexEnd + firstOffset) to referencedSize
    }
}

package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Talks to the real YouTube. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest -Dyoutube.realNetwork=true
 * Every test prints one "REAL-RESULT" line; docs/youtube-test-results.md is written from them.
 */
class RealNetworkVideoSourceTest {

    companion object {
        private lateinit var source: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            source = NewPipeVideoSource(OkHttpClient())
        }

        private const val RICK_ASTLEY_CHANNEL = "UCuAXFkgsw1L7xaCfnd5JJOw"
        private val LIVE_FALLBACKS = listOf("nI725iVsyoQ", "j9epFget1W8")

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")
    }

    @Test
    fun normalVideoHasAdaptiveTracksOf720pOrMore() = runBlocking {
        val details = source.stream("dQw4w9WgXcQ")
        val info = details.playback.info
        report(
            "normal dQw4w9WgXcQ",
            "OK: ${details.videoTracks.size} video tracks, max ${details.videoTracks.maxOf { it.height }}p, " +
                "${info.videoOnlyStreams.size} video-only, ${info.audioStreams.size} audio, ${details.subtitles.size} subtitles",
        )
        assertThat(details.video.videoId).isEqualTo("dQw4w9WgXcQ")
        assertThat(details.video.channelId).isEqualTo(RICK_ASTLEY_CHANNEL)
        assertThat(details.video.isLive).isFalse()
        assertThat(details.video.durationSeconds).isGreaterThan(200L)
        assertThat(details.videoTracks.maxOf { it.height }).isAtLeast(720)
        assertThat(info.videoOnlyStreams).isNotEmpty()
        assertThat(info.audioStreams).isNotEmpty()

        // The app re-extracts before the URLs expire (about 6 h): the handle must say when.
        val expiresInMinutes = (details.playback.expiresAtEpochMs!! - System.currentTimeMillis()) / 60_000
        report("normal dQw4w9WgXcQ expiry", "URLs expire in $expiresInMinutes min")
        assertThat(expiresInMinutes).isIn(com.google.common.collect.Range.closed(60L, 24 * 60L))
    }

    @Test
    fun suggestionsInArabicAndEnglish() = runBlocking {
        val arabic = source.suggestions("قرآن", "ar")
        val english = source.suggestions("minecraft", "en")
        report("suggestions", "ar ${arabic.size}: ${arabic.take(3)} · en ${english.size}: ${english.take(3)}")
        assertThat(arabic).isNotEmpty()
        assertThat(english).isNotEmpty()
        assertThat(english.size).isAtMost(MAX_SUGGESTIONS)
    }

    @Test
    fun searchWithoutResultsIsAnEmptyListNotAnError() = runBlocking {
        // YouTube answers these with "no results" (NPE: NothingFoundException); a typo must not open the
        // "YouTube changed something" screen.
        val outcomes = listOf(
            "qzkxjvbnwyq plmoknijbuhv zzqqxxjj" to SearchFilter.VIDEOS,
            "qzkxjvbnwyq plmoknijbuhv zzqqxxjj" to SearchFilter.ALL,
            "qzkxjvbnwyq plmoknijbuhv zzqqxxjj" to SearchFilter.PLAYLISTS,
            "xqzjwvkpfhgbm" to SearchFilter.CHANNELS,
        ).map { (query, filter) ->
            val page = source.search(query, filter)
            "$filter=${page.items.size}"
        }
        report("search without results", "OK: $outcomes (no exception)")
        assertThat(outcomes).isNotEmpty()
    }

    @Test
    fun liveStreamHasHlsManifest() = runBlocking {
        val found = source.search("live", SearchFilter.VIDEOS).items.filterIsInstance<VideoItem>().filter { it.isLive }.map { it.videoId }
        val candidates = (found.take(3) + LIVE_FALLBACKS).distinct()
        var lastError: Throwable? = null
        for (id in candidates) {
            try {
                val details = source.stream(id)
                if (!details.video.isLive) continue
                val hls = details.playback.info.hlsUrl
                report("live $id", "OK: isLive=true, HLS=${!hls.isNullOrEmpty()}, DASH=${!details.playback.info.dashMpdUrl.isNullOrEmpty()}, ${details.videoTracks.size} tracks (found via search: ${id in found})")
                assertThat(hls).isNotEmpty()
                assertThat(details.video.durationSeconds).isNull()
                return@runBlocking
            } catch (e: SourceException) {
                lastError = e
                report("live $id", "FAILED: ${e.kind} ${e.technicalName}")
            }
        }
        fail("No live stream among $candidates could be extracted; last error: $lastError")
    }

    @Test
    fun kidsVideoIsEitherPlayableOrClearlyRejected() = runBlocking {
        try {
            val details = source.stream("it3OtbTxQk0")
            val info = details.playback.info
            report("kids it3OtbTxQk0", "PLAYABLE: ${info.videoOnlyStreams.size} video-only, max ${details.videoTracks.maxOfOrNull { it.height }}p")
            assertThat(info.videoOnlyStreams).isNotEmpty()
        } catch (e: SourceException) {
            report("kids it3OtbTxQk0", "REJECTED: ${e.kind} ${e.technicalName} (${e.message})")
            assertThat(e.kind).isAnyOf(SourceErrorKind.LOW_QUALITY_ONLY, SourceErrorKind.BLOCKED_403)
        }
    }

    /** The owner's decision: in a child profile a "made for kids" video plays its muxed ≤360p file, marked. */
    @Test
    fun kidsVideoIsPlayableAtLowQualityWhenAllowed() = runBlocking {
        try {
            val details = source.stream("it3OtbTxQk0", allowLowQuality = true)
            val info = details.playback.info
            report(
                "kids it3OtbTxQk0 allowLowQuality",
                "PLAYABLE: lowQualityHeight=${details.lowQualityHeight}, ${info.videoOnlyStreams.size} video-only, " +
                    "muxed ${info.videoStreams.map { it.getResolution() }}",
            )
            // Either YouTube serves the ladder again (not marked), or the muxed file is marked with its height.
            if (info.videoOnlyStreams.isEmpty()) assertThat(details.lowQualityHeight).isAtMost(360)
            else assertThat(details.lowQualityHeight).isNull()
        } catch (e: SourceException) {
            report("kids it3OtbTxQk0 allowLowQuality", "REJECTED: ${e.kind} ${e.technicalName} (${e.message})")
            assertThat(e.kind).isNotEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        }
    }

    @Test
    fun removedVideoIsContentUnavailable() = runBlocking {
        try {
            source.stream("_PL2HJKxnOM")
            fail("expected SourceException")
        } catch (e: SourceException) {
            report("deleted _PL2HJKxnOM", "${e.kind} ${e.technicalName} (${e.message})")
            assertThat(e.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        }
    }

    @Test
    fun searchReturnsVideosAndPages() = runBlocking {
        val first = source.search("طيور الجنة", SearchFilter.VIDEOS)
        val second = source.search("طيور الجنة", SearchFilter.VIDEOS, first.next)
        val videos = first.items.filterIsInstance<VideoItem>()
        report("search", "OK: page1=${first.items.size} page2=${second.items.size} items, first=\"${videos.firstOrNull()?.title}\"")
        assertThat(videos).isNotEmpty()
        assertThat(first.next).isNotNull()
        assertThat(second.items).isNotEmpty()
        assertWithMessage("video items carry channel ids").that(videos.count { it.channelId != null }).isGreaterThan(videos.size / 2)

        val channels = source.search("Rick Astley", SearchFilter.CHANNELS).items
        assertThat(channels.filterIsInstance<ChannelItem>()).isNotEmpty()
    }

    @Test
    fun channelAndItsVideos() = runBlocking {
        val details = source.channel(RICK_ASTLEY_CHANNEL)
        val first = source.channelVideos(RICK_ASTLEY_CHANNEL)
        val second = source.channelVideos(RICK_ASTLEY_CHANNEL, first.next)
        report("channel", "OK: \"${details.channel.name}\", page1=${first.items.size} page2=${second.items.size} videos")
        assertThat(details.channel.channelId).isEqualTo(RICK_ASTLEY_CHANNEL)
        assertThat(details.channel.name).isNotEmpty()
        assertThat(first.items).isNotEmpty()
        assertThat(first.items.all { it is VideoItem && it.channelId == RICK_ASTLEY_CHANNEL }).isTrue()
        assertThat(second.items).isNotEmpty()
    }

    @Test
    fun playlistWithPaging() = runBlocking {
        // The channel's uploads playlist: long (400+ videos) and stable, so it always has a second page.
        val playlistId = "UU" + RICK_ASTLEY_CHANNEL.removePrefix("UC")
        val page = source.playlist(playlistId)
        val more = page.next?.let { source.playlist(playlistId, it) }
        report("playlist", "OK: \"${page.details.title}\" (${page.details.videoCount} videos), page1=${page.videos.size}, page2=${more?.videos?.size}")
        assertThat(page.details.title).isNotEmpty()
        assertThat(page.videos).isNotEmpty()
        assertThat(page.next).isNotNull()
        assertThat(more!!.videos).isNotEmpty()
        assertThat(more.videos.first().videoId).isNotEqualTo(page.videos.first().videoId)
    }

    @Test
    fun recentVideosFromRssOrFallback() = runBlocking {
        val videos = source.recentVideos(RICK_ASTLEY_CHANNEL)
        val fromRss = videos.all { it.durationSeconds == null } // RSS has no durations; the Videos tab has
        report("rss", "OK: ${videos.size} videos via ${if (fromRss) "RSS" else "Videos-tab fallback"}, shorts=${videos.count { it.isShortForm }}")
        assertThat(videos).isNotEmpty()
        assertThat(videos.all { it.channelId == RICK_ASTLEY_CHANNEL }).isTrue()
        assertThat(videos.all { it.publishedAtEpochMs != null }).isTrue()
    }

    @Test
    fun recentVideosFallBackToVideosTabWhenRssFails() = runBlocking {
        // RSS only accepts UC… ids, so a handle makes the feed request fail and exercises the fallback.
        val videos = source.recentVideos("@MrBeast")
        report("rss fallback (@MrBeast)", "OK: ${videos.size} videos, with durations=${videos.count { it.durationSeconds != null }}")
        assertThat(videos).isNotEmpty()
        assertThat(videos.any { it.durationSeconds != null }).isTrue()
        assertThat(videos.all { it.channelId == "UCX6OQ3DkcsbYNE6H8uQQuVA" }).isTrue()
    }

    @Test
    fun handleResolvesToChannelId() = runBlocking {
        val link = source.parseLink("https://www.youtube.com/@MrBeast") as YouTubeLink.Channel
        val id = source.resolveChannelId(link)
        report("resolve @MrBeast", "OK: $id")
        assertThat(id).isEqualTo("UCX6OQ3DkcsbYNE6H8uQQuVA")
    }
}

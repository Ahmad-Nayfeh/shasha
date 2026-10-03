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
 *   ./gradlew :youtube:testDebugUnitTest --tests RealNetworkKidsUploadsTest -Dyoutube.realNetwork=true
 *
 * What the app falls back on when a TV answers a channel's Videos tab with an error (the extractor's plain
 * `ContentNotAvailableException`; seen on a TV in Saudi Arabia for a child's home): the channel's long-form uploads playlist,
 * «UULF» + the channel id without its «UC». It must exist, carry every video's duration and be the Videos tab's list, or the
 * child's home could not keep the shorts rule (it needs the duration of every video it shows). «UU» (every upload) is checked
 * too: it is why it is not used. Every test prints "REAL-RESULT" lines.
 */
class RealNetworkKidsUploadsTest {

    companion object {
        private lateinit var source: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            source = NewPipeVideoSource(OkHttpClient())
        }

        /** Kids channels (Arabic) that a child profile of this family would have: Videos tabs of 13 to 30 videos a page. */
        private val KIDS = listOf(
            "طيور الجنة" to "UCozAuGJZ5MPoijz8IE_1Gtw",
            "Spacetoon" to "UCuQKih3Ac3NABADQKQdeV6A",
            "Cocomelon Arabic" to "UCpcZREaNdYfSzpbQmo3IiZA",
            "Karameesh" to "UCZ7V5FSAdPkRjPf64pPx9-Q",
        )
        private const val MR_BEAST = "UCX6OQ3DkcsbYNE6H8uQQuVA" // posts Shorts every day: the proof that «UU» mixes them in

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")

        private fun longForm(channelId: String) = "UULF" + channelId.drop(2)
        private fun uploads(channelId: String) = "UU" + channelId.drop(2)
    }

    private fun describe(videos: List<VideoItem>) =
        "${videos.size} videos, durations=${videos.count { it.durationSeconds != null }}, shortFlag=${videos.count { it.isShortForm }}, " +
            "dates=${videos.count { it.publishedAtEpochMs != null }}, live=${videos.count { it.isLive }}, " +
            "<=60s=${videos.count { (it.durationSeconds ?: Long.MAX_VALUE) <= 60 }}"

    @Test
    fun longFormUploadsPlaylist_existsForKidsChannels_carriesDurations_andIsTheVideosTabsTwin() = runBlocking<Unit> {
        for ((name, id) in KIDS) {
            val tab = source.channelVideos(id).items.filterIsInstance<VideoItem>()
            val first = source.playlist(longForm(id))
            val videos = first.videos
            report("UULF $name", "\"${first.details.title}\" (${first.details.videoCount} videos), page1: ${describe(videos)}, next=${first.next != null}; Videos tab: ${describe(tab)}")

            // It exists, and every row is a video of this channel with its duration (the child's shorts rule needs it).
            assertWithMessage(name).that(videos).isNotEmpty()
            assertWithMessage("$name: durations").that(videos.all { (it.durationSeconds ?: 0) > 0 || it.isLive }).isTrue()
            assertWithMessage("$name: channel").that(videos.all { it.channelId == id }).isTrue()
            assertWithMessage("$name: dates").that(videos.all { it.publishedAtEpochMs != null || it.isLive }).isTrue()
            // Long-form only: no Shorts.
            assertWithMessage("$name: shorts").that(videos.none { it.isShortForm }).isTrue()

            // The Videos tab's list: its videos are in the playlist's first page (up to a video uploaded between the two requests), in the same order, with the same durations.
            val tabIds = tab.map { it.videoId }
            val listIds = videos.map { it.videoId }
            assertWithMessage("$name: the tab's videos are the playlist's first").that(tabIds.count { it in listIds }).isAtLeast(tabIds.size * 9 / 10)
            assertWithMessage("$name: same order").that(listIds.filter { it in tabIds }).isEqualTo(tabIds.filter { it in listIds })
            val listDurations = videos.associate { it.videoId to it.durationSeconds }
            tab.filter { it.videoId in listDurations }.forEach { assertWithMessage("$name ${it.videoId}: duration").that(listDurations[it.videoId]).isEqualTo(it.durationSeconds) }

            // Paging goes on from the playlist (the channel page continues from it): a next page of other videos.
            assertWithMessage("$name: next page").that(first.next).isNotNull()
            val second = source.playlist(longForm(id), first.next)
            report("UULF $name page 2", describe(second.videos))
            assertThat(second.videos).isNotEmpty()
            assertThat(second.videos.map { it.videoId }.intersect(listIds.toSet())).isEmpty()
        }
    }

    @Test
    fun theWholeUploadsPlaylist_hasDurationsButNeverFlagsShorts_soItIsNotAWayToLeaveThemOut() = runBlocking<Unit> {
        // «UU»: every upload, Shorts and lives included. Its rows carry durations, but not the Shorts flag, so a channel's page
        // could not leave the Shorts out of it (only «UULF» is the Videos tab's list), and none is used for a child's home.
        val all = source.playlist(uploads(MR_BEAST)).videos
        val longOnly = source.playlist(longForm(MR_BEAST)).videos
        report("UU MrBeast", "all uploads: ${describe(all)}; long-form: ${describe(longOnly)}")
        assertThat(all.all { (it.durationSeconds ?: 0) > 0 || it.isLive }).isTrue()
        val shortLooking = all.count { (it.durationSeconds ?: Long.MAX_VALUE) <= 60 }
        assertWithMessage("Shorts are in the uploads playlist").that(shortLooking).isAtLeast(5)
        assertWithMessage("but none is flagged").that(all.count { it.isShortForm }).isEqualTo(0)
        assertWithMessage("and not in the long-form one").that(longOnly.count { (it.durationSeconds ?: Long.MAX_VALUE) <= 60 }).isEqualTo(0)
    }

    @Test
    fun aChannelThatDoesNotExist_failsEveryWay_withTheExtractorsPlainContentNotAvailable() = runBlocking<Unit> {
        // What the app's chain ends with when nothing answers: the first way's error, whose class name the error screen shows.
        val bogus = "UC" + "0".repeat(22)
        val outcomes = listOf<Pair<String, suspend () -> Any>>(
            "Videos tab" to { source.channelVideos(bogus) },
            "UULF" to { source.playlist(longForm(bogus)) },
            "channel page" to { source.channel(bogus) },
        ).map { (what, call) ->
            try {
                call()
                fail("$what of a channel that does not exist must fail")
                what to ""
            } catch (e: SourceException) {
                assertThat(e.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
                assertThat(e.technicalName).isEqualTo("ContentNotAvailableException")
                what to "${e.technicalName}: ${e.message}"
            }
        }
        report("bogus channel", outcomes.joinToString(" | ") { "${it.first}: ${it.second}" })
    }
}

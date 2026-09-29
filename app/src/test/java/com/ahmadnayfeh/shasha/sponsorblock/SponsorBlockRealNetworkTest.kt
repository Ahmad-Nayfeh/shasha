package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC acceptance "SponsorBlock skips a known segment in a test video", against the real SponsorBlock server:
 * lC3h3Lm3q54's locked sponsor segment [119.513, 162.837] (RESEARCH.md). Skipped unless run with:
 *   ./gradlew :app:testDebugUnitTest --tests '*SponsorBlockRealNetworkTest' -Dyoutube.realNetwork=true
 * It prints "SPONSORBLOCK" lines.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SponsorBlockRealNetworkTest {

    @Test
    fun theTestVideosSponsor_isFound_cached_andSkipped() = runBlocking<Unit> {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        val client = SponsorBlockClient(OkHttpClient())
        println("SPONSORBLOCK request ${client.requestUrl(VIDEO)}")

        val started = System.nanoTime()
        val segments = client.fetch(VIDEO)
        println("SPONSORBLOCK ${segments.size} segment(s) in ${(System.nanoTime() - started) / 1_000_000} ms: $segments")
        val sponsor = segments.firstOrNull { it.category == SponsorCategory.SPONSOR && it.action == SegmentAction.SKIP }
        assertWithMessage("the known sponsor segment of $VIDEO").that(sponsor).isNotNull()
        assertThat(sponsor!!.startMs).isEqualTo(119_513)
        assertThat(sponsor.endMs).isEqualTo(162_837)

        // Through the Room cache: asked once, then answered from the table.
        val db = inMemoryDatabase()
        try {
            var now = 1_790_596_800_000L
            val repository = SponsorBlockRepository(client, db.sponsorSegments(), now = { now })
            assertThat(repository.lookup(VIDEO)).isInstanceOf(SponsorBlockRepository.Lookup.Fetched::class.java)
            now += 60_000
            val cached = repository.lookup(VIDEO)
            assertThat(cached).isInstanceOf(SponsorBlockRepository.Lookup.Cached::class.java)
            assertThat(cached.segments).contains(sponsor)

            // And the skip itself: playing from 115 s, the player lands past 162.837 s.
            var clock = 0L
            var position = 115_000L
            val seeks = mutableListOf<Long>()
            val player = object : SkipTarget {
                override val positionMs get() = position
                override val durationMs: Long? = 465_000
                override val isPlaying = true
                override val isLive = false
                override val isEnded = false
                override fun seekExactly(positionMs: Long, automatic: Boolean) {
                    seeks += positionMs
                    position = positionMs
                }
            }
            val skipper = SkipController(undoAllowed = true, clock = { clock })
            skipper.setSegments(cached.segments.filter { it.category in SponsorCategory.DEFAULTS })
            while (position < 170_000) {
                skipper.tick(player)
                position += SkipController.TICK_MS
                clock += SkipController.TICK_MS
            }
            println("SPONSORBLOCK skipped: $seeks, notice ${skipper.notice.value}")
            assertThat(seeks).hasSize(1)
            assertThat(seeks.single()).isAtLeast(162_837)
        } finally {
            db.close()
        }
    }

    /**
     * Every segment of the default categories in the real answer for the test video's prefix (~140 videos), each
     * played from just inside its start on a player as long as SponsorBlock says the video is: all are skipped,
     * the end screens and outros that run to the end included (to the last second), except those that start in the
     * last moments of the video. Prints the count per category.
     */
    @Test
    fun theWholePrefixAnswer_isSkipped_endScreensAndOutrosIncluded() = runBlocking<Unit> {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        val client = SponsorBlockClient(OkHttpClient())
        val body = OkHttpClient().newCall(Request.Builder().url(client.requestUrl(VIDEO)).build()).execute().use { it.body.string() }
        val videos = Json.parseToJsonElement(body).jsonArray.mapNotNull { (it as? JsonObject)?.get("videoID")?.jsonPrimitive?.content }
        val counts = sortedMapOf<String, IntArray>() // category → [segments, skipped]
        val notSkipped = mutableListOf<String>()
        for (video in videos) {
            for (segment in SponsorBlockClient.parseSegments(body, video)) {
                if (segment.category !in SponsorCategory.DEFAULTS || segment.lengthMs < SkipController.MIN_SEGMENT_MS) continue
                if (segment.videoDurationMs <= 0) continue
                // The player's own length: SponsorBlock's, and half a second more when it was stored in whole seconds.
                val duration = segment.videoDurationMs + if (segment.videoDurationMs % 1000 == 0L) 500 else 0
                var position = segment.startMs + 100
                var seekedTo: Long? = null
                val player = object : SkipTarget {
                    override val positionMs get() = position
                    override val durationMs: Long? = duration
                    override val isPlaying = true
                    override val isLive = false
                    override val isEnded = false
                    override fun seekExactly(positionMs: Long, automatic: Boolean) {
                        seekedTo = positionMs
                        position = positionMs
                    }
                }
                SkipController(undoAllowed = false, clock = { 0L }).apply { setSegments(listOf(segment)) }.tick(player)
                val count = counts.getOrPut(segment.category.apiKey) { IntArray(2) }
                count[0]++
                val landed = seekedTo
                if (landed != null) {
                    count[1]++
                    assertThat(landed).isAtMost(duration - SkipController.END_GUARD_MS)
                } else {
                    notSkipped += "$video ${segment.category.apiKey} [${segment.startMs}, ${segment.endMs}] of $duration"
                    // Only one that starts in the video's last moments may play (a jump of less than half a second).
                    assertThat(segment.startMs + 100 + SkipController.MIN_JUMP_MS).isGreaterThan(duration - SkipController.END_GUARD_MS)
                }
            }
        }
        counts.forEach { (category, count) -> println("SPONSORBLOCK prefix ${SponsorBlockClient.hashPrefix(VIDEO)}: $category skipped ${count[1]} of ${count[0]}") }
        println("SPONSORBLOCK not skipped: $notSkipped")
        assertThat(counts.values.sumOf { it[0] }).isGreaterThan(50)
        assertThat(counts["outro"]?.get(1) ?: 0).isGreaterThan(0)
    }

    private companion object {
        const val VIDEO = "lC3h3Lm3q54"
    }
}

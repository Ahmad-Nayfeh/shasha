package com.ahmadnayfeh.shasha.sponsorblock

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
                override fun seekExactly(positionMs: Long) {
                    seeks += positionMs
                    position = positionMs
                }
                override fun setMuted(muted: Boolean) = Unit
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

    private companion object {
        const val VIDEO = "lC3h3Lm3q54"
    }
}

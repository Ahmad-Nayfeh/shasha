package com.ahmadnayfeh.shasha.feed

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ChannelSyncResult
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTube
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the home feed from 20 real channels over the real network (SPEC acceptance: "RSS builds the
 * home page from 20 channels"). Skipped unless run with:
 *   ./gradlew :app:testDebugUnitTest --tests '*FeedSyncRealNetworkTest' -Dyoutube.realNetwork=true
 * It prints "FEED-SYNC" lines; docs/feed-sync-results.md is written from them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FeedSyncRealNetworkTest {

    /** Popular, long-lived channels that upload often, Arabic and international. */
    private val channels = listOf(
        "UCfiwzLy-8yKzIbsmZTzxDgw" to "الجزيرة",
        "UCahpxixMCwoANAftn6IxkTg" to "العربية",
        "UCIJXOvggjKtCagMfxvcCzAA" to "سكاي نيوز عربية",
        "UCelk6aHijZq-GJBBB9YpReA" to "BBC News عربي",
        "UC30ditU5JI16o5NbFsHde_Q" to "DW عربية",
        "UCnVGseMUtv2yFNC-dQ2JdSw" to "AJ+ عربي",
        "UCozAuGJZ5MPoijz8IE_1Gtw" to "طيور الجنة",
        "UCuQKih3Ac3NABADQKQdeV6A" to "سبيستون",
        "UCNhqvQMXIgRfjAGmxQqdNRw" to "روتانا",
        "UC0jiFAzTgl17k7awGbuoYew" to "قناة المملكة",
        "UCuAXFkgsw1L7xaCfnd5JJOw" to "Rick Astley",
        "UCX6OQ3DkcsbYNE6H8uQQuVA" to "MrBeast",
        "UCsXVk37bltHxD1rDPwtNM8Q" to "Kurzgesagt",
        "UCHnyfMqiRRG1u-2MsSQLbXA" to "Veritasium",
        "UCAuUUnT6oDeKwE6v1NGQxug" to "TED",
        "UCLA_DiR1FfKNvjuUpBHmylQ" to "NASA",
        "UCBJycsmduvYEL83R_U4JriQ" to "Marques Brownlee",
        "UCXuqSBlHAE6Xw-yeJA0Tunw" to "Linus Tech Tips",
        "UCpVm7bg6pXKo1Pr6k5kxG9A" to "National Geographic",
        "UC16niRr50-MSBwiO3YDb3RA" to "BBC News",
    )

    /** The real source, counting how many channel fetches run at once. */
    private class Counting(private val real: VideoSource) : VideoSource by real {
        private val active = AtomicInteger()
        val max = AtomicInteger()

        override suspend fun recentVideos(channelId: String): List<VideoItem> {
            max.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                return real.recentVideos(channelId)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun twentyRealChannels_buildTheHomeFeed_threeAtATime() = runBlocking<Unit> {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = inMemoryDatabase()
        val source = Counting(YouTube.create(context, OkHttpClient()))
        val csv = "Channel Id,Channel Url,Channel Title\n" +
            channels.joinToString("\n") { (id, name) -> "$id,http://www.youtube.com/channel/$id,$name" }
        val imported = SubscriptionRepository(db, source).importTakeout("subscriptions.csv", csv.toByteArray())
        assertThat(imported.added).isEqualTo(20)
        val feed = FeedRepository(db, source)

        val started = System.nanoTime()
        val report = feed.syncAll()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val home = feed.observeHome().first()
        for ((id, name) in channels) {
            val outcome = when (val r = report.results.single { it.channelId == id }) {
                is ChannelSyncResult.Synced -> "fetched=${r.fetched} stored=${r.stored} withDuration=${home.count { it.channelId == id && it.durationSeconds != null }}"
                is ChannelSyncResult.Failed -> "FAILED ${r.error.kind} ${r.error.technicalName}"
            }
            println("FEED-SYNC | channel | $id | $name | $outcome")
        }
        println(
            "FEED-SYNC | total | channels=${channels.size} synced=${report.synced} failed=${report.failed} " +
                "maxConcurrent=${source.max.get()} durationMs=$elapsedMs homeItems=${home.size} " +
                "shorts=${home.count { it.isShort }} newest=${home.firstOrNull()?.publishedAtMs} oldest=${home.lastOrNull()?.publishedAtMs}",
        )

        for ((id, name) in channels) {
            val result = report.results.single { it.channelId == id }
            assertWithMessage("$name ($id): $result").that(result).isInstanceOf(ChannelSyncResult.Synced::class.java)
            assertWithMessage("$name ($id) yields items").that((result as ChannelSyncResult.Synced).fetched).isGreaterThan(0)
        }
        assertThat(source.max.get()).isAtMost(FeedRepository.MAX_PARALLEL_CHANNELS)
        assertThat(home).isNotEmpty()
        assertThat(home.map { it.publishedAtMs }).isInOrder(Comparator.reverseOrder<Long>())
        db.close()
    }
}

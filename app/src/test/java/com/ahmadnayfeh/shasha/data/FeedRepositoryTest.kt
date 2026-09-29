package com.ahmadnayfeh.shasha.data

import android.app.Application
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FeedRepositoryTest {
    private lateinit var db: ShashaDatabase
    private val source = FakeVideoSource()
    private var now = NOW_MS
    private lateinit var feed: FeedRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        feed = FeedRepository(db, source, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    private fun subscribe(vararg ids: String) = runBlocking { db.subscriptions().insertAll(ids.map { subscription(it) }) }

    private fun uploads(channel: String, count: Int, newest: Long = NOW_MS - HOUR_MS, step: Long = DAY_MS) {
        source.uploads[channel] = (0 until count).map { video("$channel-$it", channel, newest - it * step) }
    }

    @Test
    fun syncAll_buildsTheHomeFeed_newestFirstAcrossChannels() = runBlocking {
        val a = channelId(1)
        val b = channelId(2)
        subscribe(a, b)
        source.uploads[a] = listOf(video("a1", a, NOW_MS - 3 * HOUR_MS), video("a2", a, NOW_MS - 30 * HOUR_MS))
        source.uploads[b] = listOf(video("b1", b, NOW_MS - 2 * HOUR_MS), video("b2", b, NOW_MS - 50 * HOUR_MS))

        val report = feed.syncAll()

        assertThat(report.synced).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(report.stoppedEarly).isFalse()
        assertThat(feed.observeHome().first().map { it.videoId }).containsExactly("b1", "a1", "a2", "b2").inOrder()
        assertThat(db.subscriptions().get(a)!!.lastSyncAtMs).isEqualTo(NOW_MS)
        assertThat(feed.observeLastSync().first()).isEqualTo(NOW_MS)
    }

    @Test
    fun prune_keepsTheNewest30PerChannel_andNothingOlderThan90Days() = runBlocking {
        val busy = channelId(1)
        val slow = channelId(2)
        subscribe(busy, slow)
        uploads(busy, count = 40, step = HOUR_MS)
        uploads(slow, count = 15, step = 10 * DAY_MS) // 0..140 days old

        feed.syncAll()

        val items = feed.observeHome().first()
        assertThat(items.count { it.channelId == busy }).isEqualTo(FeedRepository.KEEP_PER_CHANNEL)
        assertThat(items.filter { it.channelId == busy }.map { it.videoId }).containsExactlyElementsIn((0 until 30).map { "$busy-$it" })
        assertThat(items.filter { it.channelId == slow }.map { it.videoId }).containsExactlyElementsIn((0..8).map { "$slow-$it" })

        // Ten days later the oldest stored upload of the slow channel (80 days + 1 hour) is past 90 days.
        now += 10 * DAY_MS
        feed.prune()
        assertThat(feed.observeHome().first().count { it.channelId == slow }).isEqualTo(8)
    }

    @Test
    fun oneChannelFailing_doesNotFailTheRun_andItsErrorIsKeptUntilItSyncsAgain() = runBlocking {
        val ok = channelId(1)
        val broken = channelId(2)
        subscribe(ok, broken)
        uploads(ok, 3)
        source.failures[broken] = SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", "timeout", null)

        val report = feed.syncAll()

        assertThat(report.synced).isEqualTo(1)
        assertThat((report.results.single { it.channelId == broken } as ChannelSyncResult.Failed).error.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(db.subscriptions().get(broken)!!.lastSyncError).isEqualTo("NETWORK: SocketTimeoutException")
        assertThat(feed.observeHome().first()).hasSize(3)

        // Failed channels are retried by the next run even if it comes soon; fresh ones are skipped.
        source.failures.clear()
        uploads(broken, 2)
        now += 60_000
        val retry = feed.syncAll()
        assertThat(retry.results.map { it.channelId }).containsExactly(broken)
        assertThat(retry.skipped).isEqualTo(1)
        assertThat(db.subscriptions().get(broken)!!.lastSyncError).isNull()
    }

    @Test
    fun channelsSyncedInTheLast30Minutes_areSkipped() = runBlocking {
        val fresh = channelId(1)
        val stale = channelId(2)
        db.subscriptions().insertAll(
            listOf(subscription(fresh, lastSyncAtMs = NOW_MS - 29 * 60_000), subscription(stale, lastSyncAtMs = NOW_MS - 31 * 60_000)),
        )

        val report = feed.syncAll()

        assertThat(source.fetched).containsExactly(stale)
        assertThat(report.skipped).isEqualTo(1)
        assertThat(feed.syncAll(freshForMs = 0).results).hasSize(2)
    }

    @Test
    fun atMostThreeChannelsAtATime_onBackgroundPriorityThreads() = runBlocking {
        val ids = (1..12).map(::channelId)
        subscribe(*ids.toTypedArray())
        ids.forEach { uploads(it, 2) }
        source.fetchDelayMs = 60

        val report = feed.syncAll()

        assertThat(report.synced).isEqualTo(12)
        assertThat(source.maxConcurrent.get()).isEqualTo(FeedRepository.MAX_PARALLEL_CHANNELS)
        assertThat(source.fetchPriorities.toSet()).containsExactly(Process.THREAD_PRIORITY_BACKGROUND)
    }

    @Test
    fun overlappingRuns_shareTheLimitAndDoNotRepeatWork() = runBlocking {
        val ids = (1..9).map(::channelId)
        subscribe(*ids.toTypedArray())
        ids.forEach { uploads(it, 1) }
        source.fetchDelayMs = 40

        val reports = listOf(async { feed.syncAll() }, async { feed.syncAll() }, async { feed.syncChannel(ids[0]) }).awaitAll()

        assertThat(source.maxConcurrent.get()).isAtMost(FeedRepository.MAX_PARALLEL_CHANNELS)
        // The second run found everything fresh.
        val runs = reports.take(2).map { it as SyncReport }
        assertThat(runs.sumOf { it.synced }).isEqualTo(9)
        assertThat(runs.maxOf { it.skipped }).isAtLeast(8)
    }

    @Test
    fun stopWhen_startsNothingNew_finishesWhatIsInFlight_andTheNextRunContinues() = runBlocking {
        val ids = (1..10).map(::channelId)
        subscribe(*ids.toTypedArray())
        ids.forEach { uploads(it, 1) }
        var playing = false
        source.onFetch = { if (source.fetched.size == 1) playing = true }
        source.fetchDelayMs = 50

        val first = feed.syncAll(stopWhen = { playing })

        assertThat(first.stoppedEarly).isTrue()
        val started = source.fetched.toList()
        assertThat(started.size).isIn(1..FeedRepository.MAX_PARALLEL_CHANNELS)
        assertThat(first.synced).isEqualTo(started.size) // in-flight ones were saved
        assertThat(first.remaining).isEqualTo(10 - started.size)

        playing = false
        now += 15 * 60_000
        val second = feed.syncAll(stopWhen = { playing })
        assertThat(second.stoppedEarly).isFalse()
        assertThat(second.results.map { it.channelId }).containsExactlyElementsIn(ids - started.toSet())
    }

    @Test
    fun playingBeforeTheStart_fetchesNothing() = runBlocking {
        subscribe(channelId(1))
        val report = feed.syncAll(stopWhen = { true })
        assertThat(report.stoppedEarly).isTrue()
        assertThat(source.fetched).isEmpty()
    }

    @Test
    fun aChannelRemovedWhileItsFeedLoads_leavesNothingBehind() = runBlocking {
        val a = channelId(1)
        subscribe(a)
        uploads(a, 3)
        source.onFetch = { runBlocking { db.subscriptions().delete(it) } }

        feed.syncAll()

        assertThat(db.feed().forChannel(a)).isEmpty()
    }

    @Test
    fun syncChannel_updatesTheNameAndRemovesVanishedUploads() = runBlocking {
        val a = channelId(1)
        subscribe(a)
        source.uploads[a] = listOf(video("gone", a, NOW_MS - HOUR_MS, channelName = "Renamed"), video("keep", a, NOW_MS - 2 * HOUR_MS))
        feed.syncChannel(a)
        // "gone" is newer than the oldest upload listed now, yet missing: it was deleted or made private.
        source.uploads[a] = listOf(video("new", a, NOW_MS, channelName = "Renamed"), video("keep", a, NOW_MS - 2 * HOUR_MS, channelName = "Renamed"))

        val result = feed.syncChannel(a)

        assertThat(result).isEqualTo(ChannelSyncResult.Synced(a, fetched = 2, stored = 2))
        assertThat(db.feed().forChannel(a).map { it.videoId }).containsExactly("new", "keep").inOrder()
        assertThat(db.subscriptions().get(a)!!.name).isEqualTo("Renamed")
        assertThat(feed.syncChannel(channelId(99))).isNull()
    }
}

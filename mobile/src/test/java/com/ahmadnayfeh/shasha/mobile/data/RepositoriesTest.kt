package com.ahmadnayfeh.shasha.mobile.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Subscriptions, the feed, history, watch later and resume positions on a real (in-memory) Room database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RepositoriesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = NOW_MS
    private val source = FakeVideoSource()
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(folder.root, "s.preferences_pb") }))
    }
    private val subscriptions by lazy { SubscriptionRepository(db.subscriptions(), db.feed()) { now } }
    private val feed by lazy { FeedRepository(source, db.subscriptions(), db.feed(), settings, now = { now }, concurrency = 2) }
    private val library by lazy { LibraryRepository(db.history(), db.watchLater(), db.progress()) { now } }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    // --- subscriptions -------------------------------------------------------------------------------------------

    @Test
    fun aSubscriptionIsStoredAndFound() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1", "Zeta"))
        subscriptions.subscribe(channel("UC2", "alpha"))

        assertThat(subscriptions.subscriptions.first().map { it.name }).containsExactly("alpha", "Zeta").inOrder() // by name, any case
        assertThat(subscriptions.isSubscribed("UC1").first()).isTrue()
        assertThat(subscriptions.isSubscribed("UC9").first()).isFalse()
    }

    @Test
    fun unsubscribingAlsoClearsThatChannelsUploadsFromTheFeed() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        db.feed().upsertAll(listOf(video("a", channelId = "UC1").toFeedEntity("UC1"), video("b", channelId = "UC2").toFeedEntity("UC2")))

        subscriptions.unsubscribe("UC1")

        assertThat(subscriptions.isSubscribed("UC1").first()).isFalse()
        assertThat(db.feed().observe(10).first().map { it.videoId }).containsExactly("b")
    }

    // --- the feed ------------------------------------------------------------------------------------------------

    @Test
    fun theFeedIsTheNewestUploadsOfEveryChannelNewestFirst() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        subscriptions.subscribe(channel("UC2"))
        source.onRecent = { id ->
            if (id == "UC1") listOf(video("old", channelId = "UC1", publishedAt = 1_000), video("mid", channelId = "UC1", publishedAt = 5_000))
            else listOf(video("new", channelId = "UC2", publishedAt = 9_000), video("undated", channelId = "UC2", publishedAt = null))
        }

        val outcome = feed.refresh()

        assertThat(outcome).isEqualTo(RefreshOutcome(succeeded = 2, failed = 0))
        assertThat(feed.feed.first().map { it.videoId }).containsExactly("new", "mid", "old", "undated").inOrder()
        assertThat(settings.feedRefreshedAt.first()).isEqualTo(NOW_MS)
    }

    @Test
    fun aChannelThatCannotBeReachedKeepsItsOldUploads() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        subscriptions.subscribe(channel("UC2"))
        source.onRecent = { id -> listOf(video("v-$id", channelId = id, publishedAt = 1_000)) }
        feed.refresh()
        source.onRecent = { id -> if (id == "UC1") throw failure() else listOf(video("n-$id", channelId = id, publishedAt = 2_000)) }

        val outcome = feed.refresh()

        assertThat(outcome).isEqualTo(RefreshOutcome(succeeded = 1, failed = 1))
        assertThat(feed.feed.first().map { it.videoId }).containsExactly("n-UC2", "v-UC1").inOrder()
    }

    @Test
    fun aRefreshInWhichNobodyAnsweredIsNotARefresh() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        source.onRecent = { throw failure() }

        val outcome = feed.refresh()

        assertThat(outcome.allFailed).isTrue()
        assertThat(settings.feedRefreshedAt.first()).isEqualTo(0L) // asked again at the next start
    }

    @Test
    fun theGentleRefreshOnlyAsksWhenTheFeedIsStale() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        var asked = 0
        source.onRecent = { asked++; listOf(video("a", publishedAt = 1)) }

        assertThat(feed.refreshIfStale()).isNotNull() // never refreshed
        assertThat(asked).isEqualTo(1)

        now += 10 * 60 * 1000L
        assertThat(feed.refreshIfStale()).isNull() // ten minutes: leave it alone
        assertThat(asked).isEqualTo(1)

        now += 11 * 60 * 1000L
        assertThat(feed.refreshIfStale()).isNotNull() // twenty-one minutes
        assertThat(asked).isEqualTo(2)
    }

    @Test
    fun aClockThatWentBackRefreshesToo() = runBlocking<Unit> {
        subscriptions.subscribe(channel("UC1"))
        source.onRecent = { listOf(video("a", publishedAt = 1)) }
        feed.refresh()

        now -= 3 * 60 * 60 * 1000L

        assertThat(feed.refreshIfStale()).isNotNull()
    }

    @Test
    fun refreshingWithoutSubscriptionsAsksNobodyAndClearsStrayUploads() = runBlocking<Unit> {
        db.feed().upsertAll(listOf(video("a", channelId = "UCgone").toFeedEntity("UCgone")))

        val outcome = feed.refresh()

        assertThat(outcome).isEqualTo(RefreshOutcome(0, 0))
        assertThat(db.feed().observe(10).first()).isEmpty()
    }

    @Test
    fun aNewSubscriptionsUploadsCanBeFetchedAtOnce() = runBlocking<Unit> {
        source.onRecent = { id -> listOf(video("v1", channelId = id, publishedAt = 7), video("v2", channelId = id, publishedAt = 8)) }

        feed.refreshChannel("UC5")

        assertThat(feed.feed.first().map { it.videoId }).containsExactly("v2", "v1").inOrder()
    }

    @Test
    fun aFailedFetchOfANewSubscriptionIsSilent() = runBlocking<Unit> {
        source.onRecent = { throw failure() }

        feed.refreshChannel("UC5") // must not throw

        assertThat(feed.feed.first()).isEmpty()
    }

    // --- history, watch later, resume ----------------------------------------------------------------------------

    @Test
    fun whatIsWatchedIsRememberedNewestFirstWithoutRepeats() = runBlocking<Unit> {
        library.recordWatched(video("a"))
        now += 1_000
        library.recordWatched(video("b"))
        now += 1_000
        library.recordWatched(video("a")) // again: moves to the top

        assertThat(library.historyItems.first().map { it.videoId }).containsExactly("a", "b").inOrder()

        library.removeFromHistory("a")
        assertThat(library.historyItems.first().map { it.videoId }).containsExactly("b")

        library.clearHistory()
        assertThat(library.historyItems.first()).isEmpty()
    }

    @Test
    fun clearingTheHistoryAlsoForgetsWhereEachVideoStopped() = runBlocking<Unit> {
        library.saveProgress("a", 60_000, 300_000)

        library.clearHistory()

        assertThat(library.resumePosition("a")).isNull()
    }

    @Test
    fun watchLaterToggles() = runBlocking<Unit> {
        assertThat(library.toggleWatchLater(video("a"))).isTrue()
        assertThat(library.isInWatchLater("a").first()).isTrue()
        now += 1
        library.toggleWatchLater(video("b"))
        assertThat(library.watchLaterItems.first().map { it.videoId }).containsExactly("b", "a").inOrder()

        assertThat(library.toggleWatchLater(video("a"))).isFalse()
        assertThat(library.isInWatchLater("a").first()).isFalse()

        library.clearWatchLater()
        assertThat(library.watchLaterItems.first()).isEmpty()
    }

    @Test
    fun resumeStartsWhereTheViewerStoppedUnlessItWasBarelyBegunOrAlmostOver() = runBlocking<Unit> {
        library.saveProgress("begun", 5_000, 300_000)
        library.saveProgress("middle", 120_000, 300_000)
        library.saveProgress("almost", 290_000, 300_000)

        assertThat(library.resumePosition("begun")).isNull()
        assertThat(library.resumePosition("middle")).isEqualTo(120_000)
        assertThat(library.resumePosition("almost")).isNull()
        assertThat(library.resumePosition("never")).isNull()
    }

    @Test
    fun theBarUnderAThumbnailShowsHowMuchWasWatched() = runBlocking<Unit> {
        library.saveProgress("middle", 150_000, 300_000)
        library.saveProgress("done", 299_000, 300_000)
        library.saveProgress("nolength", 10, 0) // a live stream: no bar

        val fractions = library.watchedFractions.first()

        assertThat(fractions["middle"]).isWithin(0.001f).of(0.5f)
        assertThat(fractions["done"]).isWithin(0.001f).of(1f)
        assertThat(fractions).doesNotContainKey("nolength")
    }

    @Test
    fun resumeFromIsPureArithmetic() {
        assertThat(LibraryRepository.resumeFrom(9_999, 300_000)).isNull()
        assertThat(LibraryRepository.resumeFrom(10_000, 300_000)).isEqualTo(10_000)
        assertThat(LibraryRepository.resumeFrom(285_000, 300_000)).isNull() // within 15 s of the end
        assertThat(LibraryRepository.resumeFrom(284_999, 300_000)).isEqualTo(284_999)
    }

    // --- recent searches -----------------------------------------------------------------------------------------

    @Test
    fun recentSearchesAreNewestFirstTrimmedAndWithoutRepeats() = runBlocking<Unit> {
        val searches = SearchHistoryRepository(db.searchHistory()) { now }
        searches.add("  cats ")
        now += 1
        searches.add("dogs")
        now += 1
        searches.add("cats")
        searches.add("   ") // nothing

        assertThat(searches.recent.first()).containsExactly("cats", "dogs").inOrder()

        searches.remove("cats")
        assertThat(searches.recent.first()).containsExactly("dogs")

        searches.clear()
        assertThat(searches.recent.first()).isEmpty()
    }
}

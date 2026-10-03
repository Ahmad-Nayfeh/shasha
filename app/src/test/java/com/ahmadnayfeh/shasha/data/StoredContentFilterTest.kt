package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the built-in filter holds back is not shown from what the app stored either: the feed, the history, watch later and the
 * home suggestions' file may be from before the filter existed, or from a version without it, and the screens read them back.
 * (The source is filtered too, so what is fetched now never gets there: these tests store what an older version would have.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class StoredContentFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private val profile = MutableProfile()
    private val source = FakeVideoSource()
    private var now = NOW_MS

    private val channel = channelId(1)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun suggestions(on: VideoSource, file: File) =
        HomeSuggestions(on, file, clock = { now }, playing = MutableStateFlow(false), lowPriority = { it() }, io = Dispatchers.Unconfined)

    @Test
    fun theHomeFeed_hidesStoredUploadsTheFilterHoldsBack_byTitleAndByChannelName() = runBlocking<Unit> {
        val feed = FeedRepository(db, source, clock = { now })
        db.subscriptions().insertAll(listOf(subscription(channel)))
        source.uploads[channel] = listOf(
            video("clean1", channel, NOW_MS - HOUR_MS, title = "Cooking kunafa at home"),
            video("title1", channel, NOW_MS - 2 * HOUR_MS, title = "Sexy twerking compilation"),
            video("name1", channel, NOW_MS - 3 * HOUR_MS, title = "Weekly update", channelName = "Hot Girls TV"),
            video("clean2", channel, NOW_MS - 4 * HOUR_MS, title = "Hot weather tips", channelName = "Weather Today"),
            video("arabic", channel, NOW_MS - 5 * HOUR_MS, title = "رقص شرقي مثير"),
        )

        feed.syncAll()

        // The rows are stored (the sync does not judge them: the real source is filtered already), the screens never get them.
        assertThat(db.feed().forChannel(channel).map { it.videoId }).containsExactly("clean1", "title1", "name1", "clean2", "arabic")
        assertThat(feed.observeHome().first().map { it.videoId }).containsExactly("clean1", "clean2").inOrder()
    }

    @Test
    fun theHistoryAndWatchLater_hideStoredVideosTheFilterHoldsBack() = runBlocking<Unit> {
        val watch = WatchRepository(db, profile, clock = { now })
        watch.addToHistory(video("h1", channel, NOW_MS, title = "Quran recitation"))
        now += 1_000
        watch.addToHistory(video("h2", channel, NOW_MS, title = "Belly dance show"))
        now += 1_000
        watch.addToHistory(video("h3", channel, NOW_MS, title = "Essex football highlights"))
        watch.addToWatchLater(video("w1", channel, NOW_MS, title = "Strip pole tutorial"))
        now += 1_000
        watch.addToWatchLater(video("w2", channel, NOW_MS, title = "Math lesson"))

        assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly("h3", "h1").inOrder()
        assertThat(watch.observeWatchLater().first().map { it.videoId }).containsExactly("w2")
        // Hidden, not deleted: the filter is the only thing that decides what shows, so a better list later shows it all again.
        assertThat(db.history().observe(ADULT_PROFILE_ID, 50).first().map { it.videoId }).contains("h2")
    }

    @Test
    fun theSuggestionsFile_isFilteredWhenItIsRead() = runBlocking<Unit> {
        val file = File(tmp.root, HomeSuggestions.FILE_NAME)
        val older = object : VideoSource by FakeVideoSource() {
            // What a version without the filter fetched and kept.
            override suspend fun related(videoId: String) = listOf(
                video("s1", "UCa", null, title = "Documentary about whales"),
                video("s2", "UCa", null, title = "Nude photoshoot behind the scenes"),
                video("s3", "UCa", null, title = "Cats", channelName = "OnlyFans Hub"),
            )
        }
        suggestions(older, file).refresh(listOf("seed"))
        assertThat(file.readText()).contains("Nude photoshoot")

        val restarted = suggestions(FakeVideoSource(), file)
        restarted.refresh(listOf("seed"))

        assertThat(restarted.kept.value.getValue("seed").videos.map { it.videoId }).containsExactly("s1")
    }

    @Test
    fun aHomeFeedOfOrdinaryUploads_isUntouched() = runBlocking<Unit> {
        val feed = FeedRepository(db, source, clock = { now })
        db.subscriptions().insertAll(listOf(subscription(channel)))
        source.uploads[channel] = (1..5).map { video("v$it", channel, NOW_MS - it * HOUR_MS, title = "Episode $it: a trip to Sussex and Essex") }
        feed.syncAll()
        assertThat(feed.observeHome().first()).hasSize(5)
    }
}

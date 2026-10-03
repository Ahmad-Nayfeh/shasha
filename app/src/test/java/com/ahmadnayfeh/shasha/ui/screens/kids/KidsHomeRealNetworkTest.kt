package com.ahmadnayfeh.shasha.ui.screens.kids

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.toCandidate
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelHeader
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelViewModel
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTube
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The child's home and a channel's page over the real YouTube, on a "TV" whose channel-tab request fails the way the owner's did
 * (the extractor's plain `ContentNotAvailableException`: simulated here, since it cannot be reproduced from this machine). Skipped
 * unless run with:
 *   ./gradlew :app:testDebugUnitTest --tests KidsHomeRealNetworkTest -Dyoutube.realNetwork=true
 * It prints "REAL-RESULT" lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class KidsHomeRealNetworkTest {
    private lateinit var db: ShashaDatabase
    private val viewModels = ViewModelStore()
    private val engine = PolicyEngine(Clock.systemUTC())

    private val kids = listOf(
        "UCozAuGJZ5MPoijz8IE_1Gtw" to "طيور الجنة",
        "UCuQKih3Ac3NABADQKQdeV6A" to "سبيستون",
        "UCpcZREaNdYfSzpbQmo3IiZA" to "كوكوميلون",
        "UCZ7V5FSAdPkRjPf64pPx9-Q" to "كراميش",
    )

    /** A channel id of the right shape that YouTube has no channel for: it fails everywhere. */
    private val gone = "UC" + "0".repeat(22)

    /**
     * The real source whose channel tab answers an error like the owner's TV's did ([tabFails]; a healthy TV when false); every
     * other request is the real one. Every request is noted.
     */
    private class TabFails(private val real: VideoSource, private val tabFails: Boolean = true) : VideoSource by real {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage {
            calls += "tab:$channelId${page?.let { ":next" }.orEmpty()}"
            if (!tabFails) return real.channelVideos(channelId, page)
            throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", "Got error: \"simulated: the channel tab's answer\"", null)
        }

        override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
            calls += "playlist:$playlistId${page?.let { ":next" }.orEmpty()}"
            return real.playlist(playlistId, page)
        }

        override suspend fun recentVideos(channelId: String): List<VideoItem> {
            calls += "rss:$channelId"
            return real.recentVideos(channelId)
        }
    }

    private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")

    @Before
    fun setUp() {
        assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        if (!::db.isInitialized) return
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun realSource(tabFails: Boolean = true) = TabFails(YouTube.create(ApplicationProvider.getApplicationContext<Context>(), OkHttpClient()), tabFails)

    private fun child(hideShorts: Boolean) = Profile.newChild("سارة", id = "kid").copy(
        hideShorts = hideShorts,
        allowlist = Allowlist(channels = (kids + (gone to "قناة محذوفة")).map { (id, name) -> ListedChannel(id, name) }),
    )

    private fun home(source: VideoSource, profile: Profile): KidsHomeViewModel {
        val filter = MutableStateFlow(ContentFilter.of(engine, profile))
        val feed = FeedRepository(db, source)
        val kidsFeed = KidsFeed(source, isPlaying = { false }, lowPriority = { block -> block() })
        val watch = WatchRepository(db, MutableProfile())
        return ViewModelProvider(viewModels, viewModelFactory { initializer { KidsHomeViewModel(filter, feed, kidsFeed, watch) } })[KidsHomeViewModel::class.java]
    }

    @Test
    fun aChildsHome_whoseChannelTabFails_isFilledFromTheLongFormPlaylists_withDurations_andOneGoneChannelDoesNotBlankIt() = runBlocking<Unit> {
        val source = realSource()
        val profile = child(hideShorts = true)
        // Until every channel has answered: the gone one's failure comes first, the others' playlists take a few seconds more.
        val state = withTimeout(180_000) {
            home(source, profile).state.first { it.loaded && it.failedChannels >= 1 && it.videos.map { v -> v.video.channelId }.toSet().size == kids.size }
        }

        report(
            "kids home, tab failing",
            "${state.videos.size} videos from ${state.videos.map { it.video.channelId }.distinct().size} channels; failedChannels=${state.failedChannels}; " +
                "shown shorts=${state.videos.count { (it.video.durationSeconds ?: 0) <= 180 }}; no duration=${state.videos.count { it.video.durationSeconds == null }}; " +
                "first: ${state.videos.take(3).map { "${it.video.videoId}:${it.video.durationSeconds}" }}",
        )
        assertThat(state.failure).isNull()
        assertThat(state.failedChannels).isEqualTo(1) // the gone channel only
        assertThat(state.videos.map { it.video.channelId }.toSet()).containsExactlyElementsIn(kids.map { it.first })
        // Every card is one the player would accept: a known duration, over the Shorts limit, nothing the rules refuse.
        state.videos.forEach {
            assertThat(it.video.durationSeconds).isNotNull()
            assertThat(engine.canShow(profile, it.video.toCandidate())).isEqualTo(Decision.Allow)
        }
        // Newest first.
        val times = state.videos.mapNotNull { it.video.publishedAtEpochMs }
        assertThat(times).isInOrder(Comparator.reverseOrder<Long>())
        // How it got there: the tab failed and the playlist answered, for every channel; no RSS (no durations).
        assertThat(source.calls.filter { it.startsWith("tab:") }.toSet()).containsExactlyElementsIn((kids.map { it.first } + gone).map { "tab:$it" })
        assertThat(source.calls.filter { it.startsWith("playlist:UULF") }.toSet()).containsExactlyElementsIn((kids.map { it.first } + gone).map { "playlist:UULF${it.drop(2)}" })
        assertThat(source.calls.filter { it.startsWith("rss:") }).isEmpty()
    }

    @Test
    fun aChildsHome_onAHealthyTv_isFilledFromTheVideosTab_andThePlaylistIsAskedForOnlyForTheGoneChannel() = runBlocking<Unit> {
        val source = realSource(tabFails = false)
        val profile = child(hideShorts = true)
        val state = withTimeout(180_000) {
            home(source, profile).state.first { it.loaded && it.failedChannels >= 1 && it.videos.map { v -> v.video.channelId }.toSet().size == kids.size }
        }
        report("kids home, healthy tab", "${state.videos.size} videos from ${state.videos.map { it.video.channelId }.distinct().size} channels; failedChannels=${state.failedChannels}")
        assertThat(state.failure).isNull()
        assertThat(state.failedChannels).isEqualTo(1)
        state.videos.forEach {
            assertThat(it.video.durationSeconds).isNotNull()
            assertThat(engine.canShow(profile, it.video.toCandidate())).isEqualTo(Decision.Allow)
        }
        // The usual way answered for every channel that exists: the playlist was only tried for the one that is gone.
        assertThat(source.calls.filter { it.startsWith("playlist:") }).containsExactly("playlist:UULF${gone.drop(2)}")
        assertThat(source.calls.filter { it.startsWith("rss:") }).isEmpty()
    }

    @Test
    fun aChildWhoSeesShorts_onATvWhoseChannelTabFails_stillGetsAHome() = runBlocking<Unit> {
        val source = realSource()
        val state = withTimeout(180_000) { home(source, child(hideShorts = false)).state.first { it.loaded && !it.fetching && it.failedChannels >= 1 && it.videos.isNotEmpty() } }
        report("kids home with Shorts, tab failing", "${state.videos.size} videos; failedChannels=${state.failedChannels}; rss calls=${source.calls.count { it.startsWith("rss:") }}, playlist calls=${source.calls.count { it.startsWith("playlist:") }}")
        assertThat(state.failure).isNull()
        assertThat(state.videos).isNotEmpty()
        // RSS first (the real one, or its own fallback to the tab inside the extractor, which this wrapper cannot see).
        assertThat(source.calls.filter { it.startsWith("rss:") }.toSet()).containsExactlyElementsIn((kids.map { it.first } + gone).map { "rss:$it" })
    }

    @Test
    fun aChannelsPage_whoseTabFails_startsFromThePlaylist_andPagesOnFromIt() = runBlocking<Unit> {
        val source = realSource()
        val id = kids.first().first
        val filter = MutableStateFlow(ContentFilter.of(engine, child(hideShorts = true)))
        val vm = ViewModelProvider(
            viewModels,
            viewModelFactory { initializer { ChannelViewModel(id, source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), CoroutineScope(Dispatchers.Unconfined), filter) } },
        )[ChannelViewModel::class.java]

        val first = withTimeout(120_000) { vm.items.first { !it.loadingFirst && !it.loadingMore } }
        val header = withTimeout(120_000) { vm.header.first { it !is ChannelHeader.Loading } }
        report("channel page, tab failing", "first screen ${first.items.size} videos, header=${header::class.simpleName}, error=${first.error}")
        assertThat(first.error).isNull()
        assertThat(first.items.size).isAtLeast(24)
        assertThat(header).isInstanceOf(ChannelHeader.Ready::class.java)

        vm.loadMore()
        val more = withTimeout(120_000) { vm.items.first { !it.loadingMore && it.items.size > first.items.size } }
        report("channel page, tab failing", "after loadMore ${more.items.size} videos, endReached=${more.endReached}")
        assertThat(more.moreError).isNull()
        assertThat(more.items.size).isGreaterThan(first.items.size)
        // Paging went on from the playlist, never through the tab.
        assertThat(source.calls.filter { it.startsWith("tab:") }).containsExactly("tab:$id")
        assertThat(source.calls.filter { it.startsWith("playlist:") }).contains("playlist:UULF${id.drop(2)}:next")
    }
}

package com.ahmadnayfeh.shasha.ui.paging

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.CHANNEL
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.pagesOf
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelViewModel
import com.ahmadnayfeh.shasha.ui.screens.playlist.PlaylistViewModel
import com.ahmadnayfeh.shasha.ui.screens.search.SearchViewModel
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * «Searching shows only a few videos»: a search, a channel and a playlist keep loading pages until the screen is full of
 * what the profile may see, then page by page while the list is scrolled, and stop where the source ends. A fake
 * [VideoSource][com.ahmadnayfeh.shasha.youtube.VideoSource] (ScriptedSource) answers page by page; the real view models
 * and the real rules sit on top. The page sizes are what YouTube's «all» search really gave through the extractor
 * (8, 4, 6, 2, 9, 11 ... items), and the Shorts a child's profile hides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ListPagingTest {
    private lateinit var db: ShashaDatabase
    private val source = ScriptedSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun searchModel() = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules)

    private fun channelModel() = ChannelViewModel(
        CHANNEL,
        source,
        SubscriptionRepository(db, source),
        WatchRepository(db, MutableProfile()),
        CoroutineScope(Dispatchers.Unconfined),
        rules,
    )

    private fun playlistModel() = PlaylistViewModel("PLscripted0000000000000000000000001", source, WatchRepository(db, MutableProfile()), PlayQueues(), rules)

    /** Sara: a child who may open the scripted channel and playlist; her videos hide Shorts. */
    private val child = Profile.newChild("سارة", id = "kid").copy(
        allowlist = Allowlist(
            channels = listOf(ListedChannel(CHANNEL, "Channel")),
            playlists = listOf(ListedPlaylist("PLscripted0000000000000000000000001", "Playlist")),
        ),
    )

    // ---- Search ------------------------------------------------------------------------------------------------------

    @Test
    fun search_tinyPagesFillTheFirstScreen_beforeAnyScrolling() {
        source.searchPages = pagesOf(8, 4, 6, 2, 9, 11, 2, 11)
        val vm = searchModel()
        vm.submit("minecraft")
        // 8 + 4 + 6 + 2 + 9 = 29 ≥ 24 items: five requests, in order, one at a time.
        assertThat(source.requests).containsExactly("search:0", "search:1", "search:2", "search:3", "search:4").inOrder()
        assertThat(vm.results.value.items).hasSize(29)
        assertThat(vm.results.value.loadingMore).isFalse()
        assertThat(vm.results.value.endReached).isFalse()
    }

    @Test
    fun search_aProfileHidingShorts_stillGetsAFullScreen() {
        // Pages of 20 where 15 are Shorts: 5 videos per page for a profile that hides them.
        rules.value = ContentFilter.of(engine, owner.copy(hideShorts = true))
        source.searchPages = pagesOf(20, 20, 20, 20, 20, 20, 20, 20, short = { it % 4 != 0 })
        val vm = searchModel()
        vm.submit("كرتون أطفال")
        assertThat(source.requests).hasSize(5)
        assertThat(vm.results.value.items).hasSize(25)
        assertThat(vm.results.value.items.all { (it as com.ahmadnayfeh.shasha.ui.model.VideoUi).video.isShortForm.not() }).isTrue()
    }

    @Test
    fun search_keepsLoadingWhileTheListIsScrolled_andStopsWhereTheResultsEnd() {
        source.searchPages = pagesOf(20, 20, 20, 15)
        val vm = searchModel()
        vm.submit("وثائقي")
        assertThat(source.requests).containsExactly("search:0", "search:1") // 40 ≥ 24
        assertThat(vm.results.value.items).hasSize(40)

        // The focus nears the end: a batch (12 new items needed: one page here).
        vm.loadMore()
        assertThat(vm.results.value.items).hasSize(60)
        vm.loadMore()
        assertThat(vm.results.value.items).hasSize(75)
        assertThat(vm.results.value.endReached).isTrue()
        assertThat(vm.results.value.loadingMore).isFalse()

        // At the end: no request however often the focus gets there.
        repeat(4) { vm.loadMore() }
        assertThat(source.requests).containsExactly("search:0", "search:1", "search:2", "search:3").inOrder()
    }

    @Test
    fun search_aFailedLaterPage_keepsTheResultsAndWaitsForRetry() {
        source.searchPages = pagesOf(10, 10, 10, 10)
        source.failing = setOf("search:2")
        val vm = searchModel()
        vm.submit("x")
        assertThat(vm.results.value.items).hasSize(20)
        assertThat(vm.results.value.error).isNull()
        assertThat(vm.results.value.moreError?.kind).isEqualTo(SourceErrorKind.NETWORK)
        repeat(3) { vm.loadMore() }
        assertThat(source.requests).containsExactly("search:0", "search:1", "search:2").inOrder()

        source.failing = emptySet()
        vm.retryMore()
        assertThat(vm.results.value.moreError).isNull()
        assertThat(vm.results.value.items).hasSize(40)
        assertThat(vm.results.value.endReached).isTrue()
    }

    // ---- A channel's videos --------------------------------------------------------------------------------------------

    @Test
    fun channel_aChildsProfileThatHidesMostOfEachPage_stillGetsAFullScreen() {
        rules.value = ContentFilter.of(engine, child)
        // Pages of 30 videos, four in five of them Shorts: 6 shown per page, so 24 take four pages.
        source.channelPages = pagesOf(30, 30, 30, 30, 30, 30, short = { it % 5 != 0 })
        val vm = channelModel()
        assertThat(source.requests).containsExactly("channel:0", "channel:1", "channel:2", "channel:3").inOrder()
        assertThat(vm.videos.value.items).hasSize(24)
        assertThat(vm.videos.value.loadingMore).isFalse()
    }

    @Test
    fun channel_keepsLoadingWhileScrolled_andStopsAtTheEnd() {
        source.channelPages = pagesOf(30, 30, 10)
        val vm = channelModel()
        assertThat(vm.videos.value.items).hasSize(30) // one full page is enough for the first screen
        vm.loadMore()
        assertThat(vm.videos.value.items).hasSize(60)
        vm.loadMore()
        assertThat(vm.videos.value.items).hasSize(70)
        assertThat(vm.videos.value.endReached).isTrue()
        repeat(3) { vm.loadMore() }
        assertThat(source.requests).containsExactly("channel:0", "channel:1", "channel:2").inOrder()
    }

    // ---- A playlist's videos -------------------------------------------------------------------------------------------

    @Test
    fun playlist_aChildsProfileThatHidesMostOfEachPage_stillGetsAFullScreen() {
        rules.value = ContentFilter.of(engine, child)
        source.playlistPages = pagesOf(25, 25, 25, 25, 25, 25, short = { it % 5 != 0 })
        val vm = playlistModel()
        // 5 shown per page of 25: five pages for 24.
        assertThat(source.requests).hasSize(5)
        assertThat(vm.videos.value.items).hasSize(25)
    }

    @Test
    fun playlist_keepsLoadingWhileScrolled_andStopsAtTheEnd() {
        source.playlistPages = pagesOf(100, 100, 38)
        val vm = playlistModel()
        assertThat(vm.videos.value.items).hasSize(100)
        vm.loadMore()
        vm.loadMore()
        assertThat(vm.videos.value.items).hasSize(238)
        assertThat(vm.videos.value.endReached).isTrue()
        repeat(3) { vm.loadMore() }
        assertThat(source.requests).containsExactly("playlist:0", "playlist:1", "playlist:2").inOrder()
        // The queue handed to the player is the list as loaded, and has nowhere further to continue.
        assertThat(vm.startAt(0)).isNotNull()
    }
}

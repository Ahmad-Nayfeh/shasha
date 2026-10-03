package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock

/** Search runs on submit or a filter change (never while typing), pages on demand, subscribes from results. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SearchViewModelTest {
    private lateinit var db: ShashaDatabase
    private val source = FakeUiSource()
    private lateinit var vm: SearchViewModel
    private val v = UiFixtures.videos
    private val engine = PolicyEngine(Clock.systemUTC())
    private val owner = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)
    private val rules = MutableStateFlow(ContentFilter.of(engine, owner))

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        vm = SearchViewModel(source, SubscriptionRepository(db, source), WatchRepository(db, MutableProfile()), rules)
        source.searchResult = { call ->
            when (call.page) {
                null -> ItemsPage(listOf(v[0], UiFixtures.calligraphyChannel, v[1]), PageKey("2"))
                else -> ItemsPage(listOf(v[1], v[2]), null) // v[1] again: dropped
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun blankQueriesAreNotSent() {
        vm.submit("   ")
        assertThat(source.searches).isEmpty()
        assertThat(vm.query.value).isEmpty()
    }

    @Test
    fun submitSearches_andAShortFirstPageBringsTheNextOnes() {
        vm.submit("  الخط العربي ")
        assertThat(source.searches.first()).isEqualTo(FakeUiSource.SearchCall("الخط العربي", SearchFilter.ALL, null))
        // Three results are not a screenful: the next page comes at once (PagedLoader fills the first screen), its repeat dropped.
        assertThat(source.searches.map { it.page }).containsExactly(null, PageKey("2")).inOrder()
        assertThat(vm.results.value.items.map { it.key }).containsExactly(
            "v:${v[0].videoId}", "c:${UiFixtures.calligraphyChannel.channelId}", "v:${v[1].videoId}", "v:${v[2].videoId}",
        ).inOrder()
        assertThat(vm.results.value.endReached).isTrue()

        // At the end, asking for more sends nothing.
        vm.loadMore()
        vm.loadMore()
        assertThat(source.searches).hasSize(2)
    }

    @Test
    fun resultsTheProfileHides_neverShow_evenWhenBlockedAfterTheyArrived() {
        source.searchResult = { ItemsPage(listOf(v[0], UiFixtures.calligraphyChannel, v[1]), null) }
        vm.submit("x")
        assertThat(vm.results.value.items).hasSize(3)
        // Blocked from a card's menu while the results are on screen: they go at once.
        val blocked = Blocklist(channels = listOf(ListedChannel(UiFixtures.calligraphyChannel.channelId, "فنون الخط"), ListedChannel(v[0].channelId!!, "x")))
        rules.value = ContentFilter.of(engine, owner.copy(blocklist = blocked))
        assertThat(vm.results.value.items.map { it.key }).containsExactly("v:${v[1].videoId}")
        // Before the profile is known, nothing is shown at all.
        rules.value = ContentFilter.NOTHING
        assertThat(vm.results.value.items).isEmpty()
    }

    @Test
    fun aFilterRerunsTheQueryButNotBeforeOneWasSubmitted() {
        vm.setFilter(SearchFilter.CHANNELS)
        assertThat(source.searches).isEmpty()
        vm.submit("قناة")
        vm.setFilter(SearchFilter.CHANNELS) // unchanged: nothing sent
        vm.setFilter(SearchFilter.PLAYLISTS)
        // Each run starts again from the first page (the later page that fills the screen follows it).
        assertThat(source.searches.filter { it.page == null }.map { it.filter }).containsExactly(SearchFilter.CHANNELS, SearchFilter.PLAYLISTS).inOrder()
    }

    @Test
    fun theErrorScreensBackLeavesAnEmptySearch() {
        source.searchResult = { throw com.ahmadnayfeh.shasha.youtube.SourceException(com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BOT_CHECK, "SignInConfirmNotBotException", null, null) }
        vm.submit("x")
        assertThat(vm.results.value.error?.kind).isEqualTo(com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BOT_CHECK)
        vm.clearResults()
        assertThat(vm.query.value).isEmpty()
        assertThat(vm.results.value.error).isNull()
    }

    @Test
    fun subscribeFromAChannelResult(): Unit = runBlocking {
        val channel = UiFixtures.calligraphyChannel
        // Keep the subscribed-ids flow alive, as the screen does.
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.subscribed.collect {} }
        vm.toggleSubscription(channel)
        withTimeout(5_000) { vm.subscribed.first { channel.channelId in it } }
        vm.toggleSubscription(channel)
        withTimeout(5_000) { vm.subscribed.first { channel.channelId !in it } }
        collector.cancel()
    }

    // ---- v1.4: the row of Shorts beside the results of a «الكل» search ------------------------------------------------

    private fun shorts(vararg ids: String, channel: String = "UCshortsmaker00000000001") =
        ids.map { id -> UiFixtures.short(0, "Short $id", "قناة", 30, 3_600_000L).copy(videoId = id, channelId = channel) }

    @Test
    fun aSearchOfEverything_hasAShortsRow_besideItsResults() {
        source.shortsResult = { ShortsPage(shorts("sh000000001", "sh000000002", "sh000000001"), PageKey("more")) }
        vm.submit("  الخط العربي ")
        assertThat(source.shortsSearches).containsExactly("الخط العربي")
        // No repeats, in the order YouTube gave them; the results themselves are untouched.
        assertThat(vm.shorts.value.map { it.video.videoId }).containsExactly("sh000000001", "sh000000002").inOrder()
        assertThat(vm.results.value.items).isNotEmpty()
    }

    @Test
    fun theShortsRow_followsTheProfilesRules() {
        source.shortsResult = { ShortsPage(shorts("sh000000001") + shorts("sh000000002", channel = "UCblocked0000000000000001"), null) }
        vm.submit("x")
        assertThat(vm.shorts.value.map { it.video.videoId }).containsExactly("sh000000001", "sh000000002").inOrder()

        // A channel blocked while the row is on screen: its Short goes at once.
        val blocked = Blocklist(channels = listOf(ListedChannel("UCblocked0000000000000001", "محظورة")))
        rules.value = ContentFilter.of(engine, owner.copy(blocklist = blocked))
        assertThat(vm.shorts.value.map { it.video.videoId }).containsExactly("sh000000001")

        // Where the profile hides Shorts there is no row; before the profile is known, none either.
        rules.value = ContentFilter.of(engine, owner.copy(hideShorts = true))
        assertThat(vm.shorts.value).isEmpty()
        rules.value = ContentFilter.NOTHING
        assertThat(vm.shorts.value).isEmpty()
    }

    @Test
    fun aProfileThatHidesShorts_isNotEvenAsked() {
        rules.value = ContentFilter.of(engine, owner.copy(hideShorts = true))
        source.shortsResult = { ShortsPage(shorts("sh000000001"), null) }
        vm.submit("x")
        assertThat(source.shortsSearches).isEmpty()
        assertThat(vm.shorts.value).isEmpty()
    }

    @Test
    fun theOtherFilters_nameWhatTheyWant_andHaveNoShortsRow() {
        source.shortsResult = { ShortsPage(shorts("sh000000001"), null) }
        vm.submit("x")
        assertThat(vm.shorts.value).isNotEmpty()
        vm.setFilter(SearchFilter.VIDEOS)
        assertThat(vm.shorts.value).isEmpty()
        assertThat(source.shortsSearches).hasSize(1)
        // Back to «الكل»: the row is asked for again.
        vm.setFilter(SearchFilter.ALL)
        assertThat(source.shortsSearches).hasSize(2)
        assertThat(vm.shorts.value).isNotEmpty()
    }

    @Test
    fun aNewSearchDropsTheOldShorts_andACleared_oneHasNone() {
        source.shortsResult = { q -> ShortsPage(shorts(if (q == "first") "sh000000001" else "sh000000002"), null) }
        vm.submit("first")
        assertThat(vm.shorts.value.map { it.video.videoId }).containsExactly("sh000000001")
        vm.submit("second")
        assertThat(vm.shorts.value.map { it.video.videoId }).containsExactly("sh000000002")
        vm.clearResults()
        assertThat(vm.shorts.value).isEmpty()
    }

    @Test
    fun failingToGetShorts_leavesTheResultsAlone_noErrorScreen() {
        source.shortsResult = { throw SourceException(SourceErrorKind.BLOCKED_403, "HttpStatus403", null, null) }
        vm.submit("x")
        assertThat(vm.shorts.value).isEmpty()
        assertThat(vm.results.value.error).isNull()
        assertThat(vm.results.value.items).isNotEmpty()
    }
}

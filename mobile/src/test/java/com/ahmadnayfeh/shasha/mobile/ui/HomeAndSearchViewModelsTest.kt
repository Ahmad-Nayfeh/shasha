package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeSearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.awaitReal
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.data.toSubscription
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.playlist
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.home.HomeViewModel
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private val source = FakeVideoSource()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { testSettings(File(folder.root, "s.preferences_pb"), storeScope) }
    private var now = NOW_MS

    @After
    fun tearDown() = storeScope.cancel()

    private fun viewModel() = HomeViewModel(
        SubscriptionRepository(subsDao, feedDao) { now },
        FeedRepository(source, subsDao, feedDao, settings, now = { now }),
    )

    private suspend fun subscribe(vararg ids: String) = ids.forEach { subsDao.upsert(channel(it).toSubscription(now)) }

    /** The stored lists were read and no refresh is running (the refresh's work happens on other threads). */
    private suspend fun HomeViewModel.awaitIdle() = awaitReal { state.value.let { it.loaded && !it.refreshing } }

    @Test
    fun withoutSubscriptionsThereIsNothingToAskAndTheEmptyStateShows() = runTest(main.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.awaitIdle()

        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.subscriptions).isEmpty()
        assertThat(vm.state.value.videos).isEmpty()
        assertThat(vm.state.value.refreshing).isFalse()
    }

    @Test
    fun theFeedOpensAtOnceFromWhatWasStoredAndRefreshesWhenItWasNeverRefreshed() = runTest(main.dispatcher) {
        subscribe("UC1")
        feedDao.upsertAll(listOf(video("stored", channelId = "UC1", publishedAt = 1_000).toFeedEntity("UC1")))
        val gate = CompletableDeferred<Unit>()
        source.onRecent = {
            gate.await()
            listOf(video("fresh", channelId = "UC1", publishedAt = 2_000))
        }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        // The stored uploads are there while the refresh is still running.
        awaitReal { vm.state.value.loaded }
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("stored")
        assertThat(vm.state.value.refreshing).isTrue()

        gate.complete(Unit)
        vm.awaitIdle()

        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("fresh")
        assertThat(vm.state.value.refreshing).isFalse()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun offlineKeepsWhatWasStoredAndSaysSo() = runTest(main.dispatcher) {
        subscribe("UC1")
        feedDao.upsertAll(listOf(video("stored", channelId = "UC1", publishedAt = 1_000).toFeedEntity("UC1")))
        source.onRecent = { throw failure(SourceErrorKind.NETWORK, "UnknownHostException") }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.awaitIdle()

        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("stored")
        assertThat(vm.state.value.error!!.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(vm.state.value.refreshing).isFalse()
    }

    @Test
    fun aRecentFeedIsLeftAloneButPullingDownAlwaysRefreshes() = runTest(main.dispatcher) {
        subscribe("UC1")
        val asked = AtomicInteger()
        source.onRecent = { listOf(video("a", channelId = "UC1", publishedAt = asked.incrementAndGet() * 1_000L)) }
        val vm = viewModel() // the first start: refreshes
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.awaitIdle()
        assertThat(asked.get()).isEqualTo(1)

        val again = viewModel() // a minute later: recent enough
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { again.state.collect {} }
        again.awaitIdle()
        assertThat(asked.get()).isEqualTo(1)

        again.refresh(force = true)
        awaitReal { asked.get() == 2 }
        again.awaitIdle()
        assertThat(asked.get()).isEqualTo(2)
    }

    @Test
    fun aRefreshWhileOneIsRunningIsIgnored() = runTest(main.dispatcher) {
        subscribe("UC1")
        val gate = CompletableDeferred<Unit>()
        val asked = AtomicInteger()
        source.onRecent = {
            asked.incrementAndGet()
            gate.await()
            emptyList()
        }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        awaitReal { asked.get() == 1 } // the refresh of the start is asking, and waits at the gate

        vm.refresh()
        vm.refresh()
        gate.complete(Unit)
        vm.awaitIdle()

        assertThat(asked.get()).isEqualTo(1)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVideoSource()
    private val historyDao = FakeSearchHistoryDao()
    private var now = 1_000L
    private val history = SearchHistoryRepository(historyDao) { now++ }

    private fun viewModel(initial: String? = null) = SearchViewModel(source, history, language = { "en" }, initialQuery = initial)

    private val oneVideo = ItemsPage(listOf(video("a")), null)

    @Test
    fun typingAsksForSuggestionsAfterAPauseNotAtEveryLetter() = runTest(main.dispatcher) {
        source.onSuggestions = { q, _ -> listOf("$q one", "$q two") }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.suggestions.collect {} }

        vm.onQueryChange("c")
        advanceTimeBy(100)
        vm.onQueryChange("ca")
        advanceTimeBy(100)
        vm.onQueryChange("cat")
        assertThat(source.suggestionQueries).isEmpty()

        advanceTimeBy(300)

        assertThat(source.suggestionQueries).containsExactly("cat|en")
        assertThat(vm.suggestions.value).containsExactly("cat one", "cat two").inOrder()
    }

    @Test
    fun aBlankFieldHasNoSuggestionsAndAFailedLookupIsSilent() = runTest(main.dispatcher) {
        source.onSuggestions = { _, _ -> throw failure() }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.suggestions.collect {} }

        vm.onQueryChange("   ")
        advanceTimeBy(500)
        vm.onQueryChange("cats")
        advanceTimeBy(500)

        assertThat(vm.suggestions.value).isEmpty()
        assertThat(source.suggestionQueries).containsExactly("cats|en")
    }

    @Test
    fun submittingSearchesRemembersTheQueryAndShowsTheResults() = runTest(main.dispatcher) {
        source.onSearch = { _, _, _ -> ItemsPage(listOf(video("a"), channel("UC1"), playlist("PL1")), null) }
        val vm = viewModel()

        vm.onQueryChange("  cats ")
        vm.submit()

        assertThat(vm.submitted.value).isEqualTo("cats")
        assertThat(vm.editing.value).isFalse()
        assertThat(vm.query.value).isEqualTo("cats")
        assertThat(source.searches.single()).isEqualTo(Triple("cats", SearchFilter.ALL, null))
        assertThat(vm.resultState.value.items).hasSize(3)
        assertThat(vm.resultState.value.endReached).isTrue()
        assertThat(historyDao.rows.value.map { it.query }).containsExactly("cats")
    }

    @Test
    fun aBlankQueryIsNotSearched() = runTest(main.dispatcher) {
        val vm = viewModel()

        vm.onQueryChange("   ")
        vm.submit()

        assertThat(source.searches).isEmpty()
        assertThat(vm.submitted.value).isNull()
        assertThat(vm.editing.value).isTrue()
    }

    @Test
    fun theFilterIsPartOfTheSearchAndChangingItSearchesAgain() = runTest(main.dispatcher) {
        source.onSearch = { _, filter, _ -> if (filter == SearchFilter.CHANNELS) ItemsPage(listOf(channel("UC1")), null) else oneVideo }
        val vm = viewModel()
        vm.submit("cats")

        vm.selectFilter(SearchFilter.CHANNELS)

        assertThat(source.searches.map { it.second }).containsExactly(SearchFilter.ALL, SearchFilter.CHANNELS).inOrder()
        assertThat(vm.resultState.value.items.map { it::class.simpleName }).containsExactly("ChannelItem")
        assertThat(vm.filter.value).isEqualTo(SearchFilter.CHANNELS)

        vm.selectFilter(SearchFilter.CHANNELS) // the same filter: nothing new
        assertThat(source.searches).hasSize(2)
    }

    @Test
    fun scrollingLoadsTheNextPageWithTheKeyOfTheLast() = runTest(main.dispatcher) {
        source.onSearch = { _, _, page ->
            when (page) {
                null -> ItemsPage(List(12) { video("p1-$it") }, PageKey("2"))
                else -> ItemsPage(List(12) { video("p2-$it") }, null)
            }
        }
        val vm = viewModel()
        vm.submit("cats")
        assertThat(vm.resultState.value.items).hasSize(12)

        vm.loadMore()

        assertThat(vm.resultState.value.items).hasSize(24)
        assertThat(source.searches.map { it.third }).containsExactly(null, PageKey("2")).inOrder()
        assertThat(vm.resultState.value.endReached).isTrue()
    }

    @Test
    fun aFailureShowsInTheListAndRetryAsksAgain() = runTest(main.dispatcher) {
        var fail = true
        source.onSearch = { _, _, _ -> if (fail) throw failure(SourceErrorKind.BLOCKED_403, "HTTP 403") else oneVideo }
        val vm = viewModel()

        vm.submit("cats")
        assertThat(vm.resultState.value.error!!.technicalName).isEqualTo("HTTP 403")

        fail = false
        vm.retry()
        assertThat(vm.resultState.value.error).isNull()
        assertThat(vm.resultState.value.items).hasSize(1)
    }

    @Test
    fun aSharedTextIsSearchedAtOnce() = runTest(main.dispatcher) {
        source.onSearch = { _, _, _ -> oneVideo }

        val vm = viewModel(initial = "shared words")

        assertThat(vm.submitted.value).isEqualTo("shared words")
        assertThat(source.searches.single().first).isEqualTo("shared words")
    }

    @Test
    fun recentSearchesCanBeRemovedOrCleared() = runTest(main.dispatcher) {
        history.add("a")
        history.add("b")
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.recent.collect {} }
        assertThat(vm.recent.value).containsExactly("b", "a").inOrder()

        vm.removeRecent("b")
        assertThat(vm.recent.value).containsExactly("a")

        vm.clearRecent()
        assertThat(vm.recent.value).isEmpty()
    }

    @Test
    fun tappingTheFieldAgainGoesBackToTheSuggestionsKeepingTheResults() = runTest(main.dispatcher) {
        source.onSearch = { _, _, _ -> oneVideo }
        val vm = viewModel()
        vm.submit("cats")

        vm.startEditing()

        assertThat(vm.editing.value).isTrue()
        assertThat(vm.resultState.value.items).hasSize(1)

        vm.clearQuery()
        assertThat(vm.query.value).isEmpty()
    }

    @Test
    fun suggestionsComeInTheAppsLanguageElseThePhones() {
        assertThat(SearchViewModel.suggestionLanguage("ar", "en")).isEqualTo("ar")
        assertThat(SearchViewModel.suggestionLanguage("en", "ar")).isEqualTo("en")
        assertThat(SearchViewModel.suggestionLanguage(null, "ar")).isEqualTo("ar")
        assertThat(SearchViewModel.suggestionLanguage(null, "fr")).isEqualTo("en") // YouTube's suggestions come in these two
    }
}

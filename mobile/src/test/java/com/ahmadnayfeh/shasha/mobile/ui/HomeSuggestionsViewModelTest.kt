package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.awaitReal
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.data.DAY_MS
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.HomeSuggestions
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.data.toHistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.toSubscription
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.home.HomeState
import com.ahmadnayfeh.shasha.mobile.ui.home.HomeViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Home's «مقترحة لك» and Shorts row over the real [HomeSuggestions]: seeds from the history, related lists from the source,
 * the owner's recency weighting, and Shorts on or off, with the channels' own uploads beside them. Nothing here may hold the feed
 * back: it is shown from what is stored while the suggestions are still being fetched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSuggestionsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val subsDao = FakeSubscriptionDao()
    private val feedDao = FakeFeedDao()
    private val historyDao = FakeHistoryDao()
    private val source = FakeVideoSource()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { testSettings(File(folder.root, "s.preferences_pb"), storeScope) }
    private val showShorts = MutableStateFlow(true)
    private var now = NOW_MS

    @After
    fun tearDown() = storeScope.cancel()

    private fun viewModel(): HomeViewModel {
        val library = LibraryRepository(historyDao, FakeWatchLaterDao(), FakeProgressDao()) { now }
        return HomeViewModel(
            SubscriptionRepository(subsDao, feedDao) { now },
            FeedRepository(source, subsDao, feedDao, settings, now = { now }),
            HomeSuggestions(library, source, file = null, clock = { now }),
            showShorts,
        )
    }

    private fun short(id: String, channelId: String? = null) = video(id).copy(isShortForm = true, channelId = channelId ?: video(id).channelId)

    private suspend fun watch(id: String, daysAgo: Double = 0.0) =
        historyDao.upsert(video(id).toHistoryEntity(now - (daysAgo * DAY_MS).toLong()))

    /** Follows [id] with [uploads] already stored and the feed refreshed a minute ago: opening the Home asks the network for nothing. */
    private suspend fun follow(id: String, vararg uploads: VideoItem) {
        subsDao.upsert(channel(id).toSubscription(now))
        feedDao.upsertAll(uploads.map { it.toFeedEntity(id) })
        settings.setFeedRefreshedAt(now - 60_000)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.open(): HomeViewModel {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        awaitReal { vm.state.value.let { it.loaded && !it.refreshing } }
        return vm
    }

    /** Waits until the suggestions have been fetched and ranked into the state. */
    private suspend fun HomeViewModel.awaitSuggestions(cond: (HomeState) -> Boolean = { it.hasMore }) = awaitReal { cond(state.value) }

    @Test
    fun theSuggestionsAppearBesideTheFeedAndTheFeedIsNotWaitingForThem() = runTest(main.dispatcher) {
        follow("UC1", video("up1", channelId = "UC1", publishedAt = 1_000))
        watch("seen")
        val gate = CompletableDeferred<Unit>()
        source.onRelated = { gate.await(); listOf(video("s1"), video("s2")) }
        val vm = open()

        // The feed is there, the suggestions are on their way.
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("up1")
        assertThat(vm.state.value.suggestions).isEmpty()

        gate.complete(Unit)
        vm.awaitSuggestions()

        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("s1", "s2").inOrder()
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("up1")
        assertThat(vm.state.value.hasMore).isTrue()
    }

    @Test
    fun theNewerTheViewingTheHigherItsSuggestionsStand() = runTest(main.dispatcher) {
        watch("old", daysAgo = 20.0)
        watch("new", daysAgo = 0.0)
        source.onRelated = { id -> if (id == "old") listOf(video("X"), video("Y")) else listOf(video("Z"), video("X")) }
        val vm = open()
        vm.awaitSuggestions()
        awaitReal { vm.state.value.suggestions.size == 3 }

        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("Z", "X", "Y").inOrder()
    }

    @Test
    fun aSuggestionTheFeedAlreadyShowsIsNotShownTwice() = runTest(main.dispatcher) {
        follow("UC1", video("dup", channelId = "UC1", publishedAt = 1_000))
        watch("seen")
        source.onRelated = { listOf(video("dup"), video("other")) }
        val vm = open()
        vm.awaitSuggestions()

        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("other")
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("dup")
    }

    @Test
    fun theShortsOfTheChannelsAndTheSuggestedOnesShareTheRowInTurns_theFeedKeepsOnlyItsVideos() = runTest(main.dispatcher) {
        follow("UC1", video("up1", channelId = "UC1", publishedAt = 3_000), short("fs1", "UC1").copy(publishedAtEpochMs = 2_000), short("fs2", "UC1").copy(publishedAtEpochMs = 1_000))
        watch("seen")
        source.onRelated = { listOf(short("ss1"), video("s1"), short("ss2")) }
        val vm = open()
        vm.awaitSuggestions { it.shorts.size == 4 }

        assertThat(vm.state.value.shorts.map { it.videoId }).containsExactly("fs1", "ss1", "fs2", "ss2").inOrder()
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("up1") // the Shorts are not in the 16:9 list
        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("s1")
    }

    @Test
    fun withShortsOffThereAreNoneAnywhereOnTheHome_theVideosStay() = runTest(main.dispatcher) {
        showShorts.value = false
        follow("UC1", video("up1", channelId = "UC1", publishedAt = 2_000), short("fs1", "UC1").copy(publishedAtEpochMs = 1_000))
        watch("seen")
        source.onRelated = { listOf(short("ss1"), video("s1")) }
        val vm = open()
        vm.awaitSuggestions()

        assertThat(vm.state.value.shorts).isEmpty()
        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("s1")
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("up1") // a Short of a channel is not shown as a video either

        // Turned on again, they come back without asking anyone again.
        showShorts.value = true
        awaitReal { vm.state.value.shorts.map { it.videoId } == listOf("fs1", "ss1") }
    }

    @Test
    fun withoutChannelsTheSuggestionsAreTheHomeAndNothingIsSaidToBeEmpty() = runTest(main.dispatcher) {
        watch("seen")
        source.onRelated = { listOf(video("s1")) }
        val vm = open()
        vm.awaitSuggestions()

        assertThat(vm.state.value.subscriptions).isEmpty()
        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("s1")
        assertThat(vm.state.value.hasMore).isTrue()
    }

    @Test
    fun withoutChannelsOrHistoryThereIsNothingToFetchAndNothingToShow() = runTest(main.dispatcher) {
        var asked = 0
        source.onRelated = { asked++; listOf(video("s1")) }
        val vm = open()

        assertThat(vm.state.value.hasMore).isFalse()
        assertThat(vm.state.value.suggesting).isFalse()
        assertThat(vm.state.value.subscriptions).isEmpty()
        assertThat(asked).isEqualTo(0)
    }

    @Test
    fun whileTheFirstListsAreOnTheirWayAHomeWithoutChannelsKnowsItIsWaiting() = runTest(main.dispatcher) {
        watch("seen")
        val gate = CompletableDeferred<Unit>()
        source.onRelated = { gate.await(); listOf(video("s1")) }
        val vm = open()

        awaitReal { vm.state.value.suggesting }
        assertThat(vm.state.value.hasMore).isFalse()

        gate.complete(Unit)
        vm.awaitSuggestions()
        assertThat(vm.state.value.suggesting).isFalse()
    }

    @Test
    fun aFailedLookupLeavesTheFeedAloneAndPullingDownAsksAgain() = runTest(main.dispatcher) {
        follow("UC1", video("up1", channelId = "UC1", publishedAt = 1_000))
        watch("seen")
        var fail = true
        val asked = java.util.concurrent.atomic.AtomicInteger()
        source.onRelated = { asked.incrementAndGet(); if (fail) throw com.ahmadnayfeh.shasha.mobile.failure() else listOf(video("s1")) }
        val vm = open()
        awaitReal { asked.get() == 1 && !vm.state.value.suggesting }
        assertThat(vm.state.value.suggestions).isEmpty()
        assertThat(vm.state.value.videos.map { it.videoId }).containsExactly("up1")
        assertThat(vm.state.value.error).isNull() // a suggestion that did not come is not the Home's error

        fail = false
        vm.refresh(force = true)
        vm.awaitSuggestions()

        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("s1")
    }

    @Test
    fun watchingSomethingNewChangesTheSuggestionsWhileTheHomeIsOpen() = runTest(main.dispatcher) {
        watch("a", daysAgo = 1.0)
        source.onRelated = { id -> listOf(video("from-$id")) }
        val vm = open()
        vm.awaitSuggestions()
        assertThat(vm.state.value.suggestions.map { it.videoId }).containsExactly("from-a")

        watch("b")
        awaitReal { vm.state.value.suggestions.map { it.videoId } == listOf("from-b", "from-a") }
    }
}

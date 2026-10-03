package com.ahmadnayfeh.shasha.mobile.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.FakeFeedDao
import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeSearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeSubscriptionDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.data.toHistoryEntity
import com.ahmadnayfeh.shasha.mobile.data.toWatchLaterEntity
import com.ahmadnayfeh.shasha.mobile.testSettings
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The built-in content filter as the phone app's search meets it, in every setting: the screen is given the filtered source
 * ([SafeVideoSource], what AppContainer's `videoSource` is). A search it holds back is a search without results, the screen's
 * ordinary «لا توجد نتائج» state: no error, no word about why, and YouTube is never asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplicitSearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val extractor = FakeVideoSource()
    private val historyDao = FakeSearchHistoryDao()
    private var now = 1_000L
    private val history = SearchHistoryRepository(historyDao) { now++ }

    private fun viewModel(initial: String? = null) = SearchViewModel(SafeVideoSource(extractor), history, language = { "en" }, initialQuery = initial)

    private fun List<SourceItem>.titles() = filterIsInstance<VideoItem>().map { it.title }

    @Test
    fun anExplicitQueryIsASearchWithoutResults_notAnError_andYouTubeIsNeverAsked() = runTest(main.dispatcher) {
        extractor.onSearch = { _, _, _ -> ItemsPage(listOf(video("a", title = "Cooking")), PageKey("2")) }
        val vm = viewModel()

        vm.onQueryChange("Sexy twerking")
        vm.submit()

        val state = vm.resultState.value
        assertThat(state.items).isEmpty()
        assertThat(state.error).isNull()
        assertThat(state.started).isTrue() // the screen shows «لا توجد نتائج», not a spinner that never ends
        assertThat(state.loading).isFalse()
        assertThat(state.endReached).isTrue()
        assertThat(vm.submitted.value).isEqualTo("Sexy twerking")
        assertThat(extractor.searches).isEmpty()
    }

    @Test
    fun theSameQueryBehindSymbolsAndInOtherLanguages_isAskedOfNobody() = runTest(main.dispatcher) {
        val vm = viewModel()
        for (query in listOf("s e x y   t w e r k i n g", "S3XY TW3RK", "سكس", "رقص شرقي", "p0rn", "🔥sexy dance🔥", "xxx", "belly dance", "sks arabi")) {
            vm.submit(query)
            assertThat(vm.resultState.value.items).isEmpty()
            assertThat(vm.resultState.value.error).isNull()
        }
        assertThat(extractor.searches).isEmpty()
    }

    @Test
    fun anOrdinarySearch_losesOnlyItsExplicitResults_andKeepsTheNextPage() = runTest(main.dispatcher) {
        extractor.onSearch = { _, _, page ->
            when (page) {
                null -> ItemsPage(listOf(video("x1", title = "Hot girls dancing"), video("k1", title = "تعلم الرياضيات للمبتدئين")), PageKey("2"))
                else -> ItemsPage(listOf(video("x2", title = "Nude yoga"), video("k2", title = "Essex football highlights")), null)
            }
        }
        val vm = viewModel()

        vm.submit("dance")
        vm.loadMore()

        assertThat(extractor.searches.map { it.third }).containsExactly(null, PageKey("2")).inOrder()
        assertThat(vm.resultState.value.items.titles()).containsExactly("تعلم الرياضيات للمبتدئين", "Essex football highlights").inOrder()
        assertThat(vm.resultState.value.endReached).isTrue()
    }

    @Test
    fun anExplicitQueryIsNotKeptAsARecentSearch_anOrdinaryOneIs() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.submit("sexy twerking")
        vm.submit("الخط العربي")
        assertThat(historyDao.rows.value.map { it.query }).containsExactly("الخط العربي")
    }

    @Test
    fun aTextSharedToTheAppIsJudgedLikeATypedOne() = runTest(main.dispatcher) {
        val vm = viewModel(initial = "porn videos")

        assertThat(vm.submitted.value).isEqualTo("porn videos")
        assertThat(vm.resultState.value.items).isEmpty()
        assertThat(vm.resultState.value.error).isNull()
        assertThat(extractor.searches).isEmpty()
    }

    @Test
    fun suggestionsForAnExplicitStart_areNeverAskedFor_andOrdinaryOnesLoseTheirExplicitLines() = runTest(main.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.suggestions.collect {} }

        vm.onQueryChange("porn")
        advanceTimeBy(500)
        assertThat(extractor.suggestionQueries).isEmpty()
        assertThat(vm.suggestions.value).isEmpty()

        extractor.onSuggestions = { _, _ -> listOf("dance tutorial", "sexy dance", "dance for kids", "belly dance", "رقص شرقي") }
        vm.onQueryChange("dance")
        advanceTimeBy(500)
        assertThat(extractor.suggestionQueries).containsExactly("dance|en")
        assertThat(vm.suggestions.value).containsExactly("dance tutorial", "dance for kids").inOrder()
    }

    @Test
    fun theRecentSearchesTheRepositoryReturns_leaveOutWhatAnOlderVersionKept() = runTest(main.dispatcher) {
        // Rows written before the filter existed.
        historyDao.rows.value = listOf("porn videos", "قرآن كريم", "sexy twerking", "minecraft").mapIndexed { i, q ->
            com.ahmadnayfeh.shasha.mobile.data.db.SearchHistoryEntity(q, searchedAt = 100L - i)
        }
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.recent.collect {} }
        assertThat(vm.recent.value).containsExactly("قرآن كريم", "minecraft").inOrder()
    }
}

/** What the phone app stored before the filter existed (or by a version without it) is not shown by the lists that read it back. */
class ExplicitStoredContentTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = storeScope.cancel()

    @Test
    fun theHomeFeed_leavesOutStoredUploadsTheFilterHoldsBack_byTitleAndByChannelName() = runBlocking<Unit> {
        val feedDao = FakeFeedDao()
        feedDao.upsertAll(
            listOf(
                video("ok1", title = "Cooking kunafa at home", publishedAt = 5_000),
                video("bad1", title = "Sexy twerking compilation", publishedAt = 4_000),
                video("bad2", title = "Weekly update", channelName = "Hot Girls TV", publishedAt = 3_000),
                video("ok2", title = "Hot weather tips", channelName = "Weather Today", publishedAt = 2_000),
                video("bad3", title = "رقص شرقي مثير", publishedAt = 1_000),
            ).map { it.toFeedEntity("UC1") },
        )
        val feed = FeedRepository(FakeVideoSource(), FakeSubscriptionDao(), feedDao, testSettings(File(folder.root, "s.preferences_pb"), storeScope))

        assertThat(feed.feed.first().map { it.videoId }).containsExactly("ok1", "ok2").inOrder()
        // Hidden, not deleted: the filter alone decides what shows.
        assertThat(feedDao.rows.value).hasSize(5)
    }

    @Test
    fun historyAndWatchLater_leaveOutVideosTheFilterHoldsBack() = runBlocking<Unit> {
        val historyDao = FakeHistoryDao()
        val watchLaterDao = FakeWatchLaterDao()
        val library = LibraryRepository(historyDao, watchLaterDao, FakeProgressDao())
        historyDao.upsert(video("h1", title = "Quran recitation").toHistoryEntity(NOW_MS))
        historyDao.upsert(video("h2", title = "Belly dance show").toHistoryEntity(NOW_MS - 1))
        historyDao.upsert(video("h3", title = "Essex football highlights").toHistoryEntity(NOW_MS - 2))
        watchLaterDao.upsert(video("w1", title = "Strip pole tutorial").toWatchLaterEntity(NOW_MS))
        watchLaterDao.upsert(video("w2", title = "Math lesson").toWatchLaterEntity(NOW_MS - 1))

        assertThat(library.historyItems.first().map { it.videoId }).containsExactly("h1", "h3").inOrder()
        assertThat(library.watchLaterItems.first().map { it.videoId }).containsExactly("w2")
    }

    @Test
    fun recentSearches_leaveOutAndNeverKeepWhatTheFilterHoldsBack() = runBlocking<Unit> {
        val dao = FakeSearchHistoryDao()
        var now = 1_000L
        val searches = SearchHistoryRepository(dao) { now++ }
        searches.add("sexy twerking")
        searches.add("   ")
        searches.add("minecraft")
        assertThat(dao.rows.value.map { it.query }).containsExactly("minecraft")
        assertThat(searches.recent.first()).containsExactly("minecraft")
    }
}

/** The real wiring: every screen of the phone app asks YouTube through the container's one `videoSource`, so that is where the filter is. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MobileContainerSafetyTest {

    @Test
    fun theContainersVideoSource_isTheFilteredOne() = runBlocking<Unit> {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            assertThat(container.videoSource).isInstanceOf(SafeVideoSource::class.java)
            // Offline: a search the filter holds back is answered without a request, as a search without results.
            val page = container.videoSource.search("Sexy twerking")
            assertThat(page.items).isEmpty()
            assertThat(page.next).isNull()
            assertThat(container.videoSource.suggestions("porn", "en")).isEmpty()
        } finally {
            container.appScope.cancel()
        }
    }
}

package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeSearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.playlist
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchRows
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private fun short(id: String) = video(id).copy(isShortForm = true)

/**
 * The Shorts of a search: those among the results and those YouTube finds for the query ([com.ahmadnayfeh.shasha.youtube.VideoSource.searchShorts])
 * are a row of their own in the general search, none in the other filters or when Shorts are off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchShortsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVideoSource()
    private val showShorts = MutableStateFlow(true)
    private val history = SearchHistoryRepository(FakeSearchHistoryDao()) { 1L }

    private fun viewModel() = SearchViewModel(source, history, language = { "en" }, showShorts = showShorts)

    private fun shortsPage(vararg ids: String) = ShortsPage(ids.map { short(it) }, PageKey("more"))

    private val mixed = listOf(video("v1"), short("s1"), channel("UC1"), video("v2"), short("s2"), playlist("PL1"))

    @Test
    fun theShortsAreARowOfTheirOwnInTheGeneralSearch_theRestOfTheListIsUnchanged() {
        val rows = SearchRows.of(mixed, SearchFilter.ALL, showShorts = true)

        assertThat(rows.shorts.map { it.videoId }).containsExactly("s1", "s2").inOrder()
        assertThat(rows.items.map { it::class.simpleName }).containsExactly("VideoItem", "ChannelItem", "VideoItem", "PlaylistItem").inOrder()
    }

    @Test
    fun withShortsOffNoneIsInTheList_andThereIsNoRow() {
        val rows = SearchRows.of(mixed, SearchFilter.ALL, showShorts = false)

        assertThat(rows.shorts).isEmpty()
        assertThat(rows.items).hasSize(4)
        assertThat(rows.items.none { it is com.ahmadnayfeh.shasha.youtube.VideoItem && it.isShortForm }).isTrue()
    }

    @Test
    fun theOtherFiltersAskForVideosChannelsOrPlaylistsAndShowNoShorts() {
        for (filter in listOf(SearchFilter.VIDEOS, SearchFilter.CHANNELS, SearchFilter.PLAYLISTS)) {
            val rows = SearchRows.of(mixed, filter, showShorts = true)

            assertThat(rows.shorts).isEmpty()
            assertThat(rows.items).hasSize(4)
        }
    }

    @Test
    fun aSearchWithNothingButShortsIsNotEmpty() {
        val rows = SearchRows.of(listOf(short("s1")), SearchFilter.ALL, showShorts = true)

        assertThat(rows.items).isEmpty()
        assertThat(rows.shorts).hasSize(1)
    }

    @Test
    fun theFoundShortsFollowThoseAmongTheResults_andOnlyTheGeneralSearchWithShortsOnShowsThem() {
        val found = listOf(short("f1"), short("s1"))

        assertThat(SearchRows.of(mixed, SearchFilter.ALL, showShorts = true, found = found).shorts.map { it.videoId })
            .containsExactly("s1", "s2", "f1").inOrder()
        assertThat(SearchRows.of(mixed, SearchFilter.ALL, showShorts = false, found = found).shorts).isEmpty()
        assertThat(SearchRows.of(mixed, SearchFilter.VIDEOS, showShorts = true, found = found).shorts).isEmpty()
        // A search whose results hold no Short still has the row of the found ones.
        val none = SearchRows.of(emptyList(), SearchFilter.ALL, showShorts = true, found = found)
        assertThat(none.items).isEmpty()
        assertThat(none.shorts).hasSize(2)
    }

    @Test
    fun theViewModelFollowsTheResultsTheFilterAndTheSetting() = runTest(main.dispatcher) {
        // A first page of 12 results (what one load asks for) holds one Short; the second page holds another.
        source.onSearch = { _, _, page ->
            if (page == null) ItemsPage(List(11) { video("p1-$it") } + short("s1"), PageKey("2")) else ItemsPage(listOf(short("s2"), video("v2")), null)
        }
        val vm = viewModel()

        vm.submit("cats")
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("s1")
        assertThat(vm.rows.value.items).hasSize(11)

        // The next page adds to both, and the row grows where it is.
        vm.loadMore()
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("s1", "s2").inOrder()
        assertThat(vm.rows.value.items).hasSize(12)

        // Turning Shorts off takes the row away at once, without searching again.
        val asked = source.searches.size
        showShorts.value = false
        assertThat(vm.rows.value.shorts).isEmpty()
        assertThat(vm.rows.value.items).hasSize(12)
        assertThat(source.searches).hasSize(asked)

        // Another filter shows no row either, and brings it back when the general search is chosen again.
        showShorts.value = true
        vm.selectFilter(SearchFilter.VIDEOS)
        assertThat(vm.rows.value.shorts).isEmpty()
        vm.selectFilter(SearchFilter.ALL)
        assertThat(vm.rows.value.shorts).isNotEmpty()
    }

    @Test
    fun theShortsYouTubeFindsJoinTheRow_afterThoseAmongTheResults_withoutRepeats() = runTest(main.dispatcher) {
        source.onSearch = { _, _, _ -> ItemsPage(listOf(video("v1"), short("s1")), null) }
        // The same Short twice (pages overlap), one that is among the results too, and an item that is no Short at all.
        source.onSearchShorts = { _, _ -> ShortsPage(listOf(short("f1"), short("s1"), video("odd"), short("f2"), short("f1")), PageKey("2")) }
        val vm = viewModel()

        vm.submit("cats")

        // The first page only, for the query that was submitted.
        assertThat(source.shortsSearches).containsExactly("cats" to null)
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("s1", "f1", "f2").inOrder()
        assertThat(vm.rows.value.items.map { (it as VideoItem).videoId }).containsExactly("v1")
    }

    @Test
    fun aFailureOfTheShortsIsNoErrorScreen_theResultsStayAndNoRowShows() = runTest(main.dispatcher) {
        source.onSearch = { _, _, _ -> ItemsPage(listOf(video("v1")), null) }
        source.onSearchShorts = { _, _ -> throw failure(SourceErrorKind.RATE_LIMITED) }
        val vm = viewModel()

        vm.submit("cats")

        assertThat(source.shortsSearches).hasSize(1)
        assertThat(vm.rows.value.shorts).isEmpty()
        assertThat(vm.rows.value.items).hasSize(1)
        assertThat(vm.resultState.value.error).isNull()
    }

    @Test
    fun onlyTheGeneralSearchAsksForShorts_andAnotherFilterDropsTheRow() = runTest(main.dispatcher) {
        source.onSearchShorts = { _, _ -> shortsPage("f1") }
        val vm = viewModel()
        vm.selectFilter(SearchFilter.VIDEOS)

        vm.submit("cats")
        assertThat(source.shortsSearches).isEmpty()
        assertThat(vm.rows.value.shorts).isEmpty()

        vm.selectFilter(SearchFilter.ALL)
        assertThat(source.shortsSearches).hasSize(1)
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("f1")

        vm.selectFilter(SearchFilter.CHANNELS)
        assertThat(vm.rows.value.shorts).isEmpty()
        assertThat(source.shortsSearches).hasSize(1)
    }

    @Test
    fun withShortsOffYouTubeIsNotAskedForThem_andTurningThemOnBringsTheRow() = runTest(main.dispatcher) {
        showShorts.value = false
        source.onSearchShorts = { _, _ -> shortsPage("f1") }
        val vm = viewModel()

        vm.submit("cats")
        assertThat(source.shortsSearches).isEmpty()
        assertThat(vm.rows.value.shorts).isEmpty()

        // Turned on while the results are shown: the Shorts are asked for then, and the row appears.
        showShorts.value = true
        assertThat(source.shortsSearches).hasSize(1)
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("f1")
    }

    @Test
    fun aSlowAnswerOfAnEarlierSearchNeverLandsUnderTheNewerQuery() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.onSearchShorts = { query, _ ->
            if (query == "cats") {
                gate.await()
                shortsPage("old1")
            } else {
                shortsPage("new1")
            }
        }
        val vm = viewModel()

        vm.submit("cats") // waits at the gate
        vm.submit("dogs")
        gate.complete(Unit)

        assertThat(source.shortsSearches.map { it.first }).containsExactly("cats", "dogs").inOrder()
        assertThat(vm.rows.value.shorts.map { it.videoId }).containsExactly("new1")
    }
}

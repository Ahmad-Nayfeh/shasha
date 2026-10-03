package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeSearchHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.channel
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.mobile.playlist
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchRows
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private fun short(id: String) = video(id).copy(isShortForm = true)

/** The Shorts among the results of a search: a row of their own in the general search, none in the other filters or when Shorts are off. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchShortsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeVideoSource()
    private val showShorts = MutableStateFlow(true)
    private val history = SearchHistoryRepository(FakeSearchHistoryDao()) { 1L }

    private fun viewModel() = SearchViewModel(source, history, language = { "en" }, showShorts = showShorts)

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
}

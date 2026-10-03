package com.ahmadnayfeh.shasha.mobile.common

import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PagedLoaderTest {

    /** A source of [pages] (lists of numbers); the key of page n is "n". */
    private class Pages(val pages: List<List<Int>>) {
        val asked = ArrayList<PageKey?>()

        suspend fun fetch(key: PageKey?): Pair<List<Int>, PageKey?> {
            asked += key
            val index = key?.id?.toInt() ?: 0
            return pages[index] to if (index + 1 < pages.size) PageKey("${index + 1}") else null
        }
    }

    @Test
    fun loadsPageByPageUntilTheSourceEnds() = runTest(UnconfinedTestDispatcher()) {
        val pages = Pages(listOf(List(12) { it }, List(12) { 100 + it }, listOf(200)))
        val loader = PagedLoader(this, key = { it }, minPerLoad = 12, fetch = pages::fetch)

        loader.loadMore()
        assertThat(loader.state.value.items).hasSize(12)
        assertThat(loader.state.value.started).isTrue()
        assertThat(loader.state.value.endReached).isFalse()

        loader.loadMore()
        loader.loadMore()
        assertThat(loader.state.value.items).hasSize(25)
        assertThat(loader.state.value.endReached).isTrue()

        loader.loadMore() // at the end: nothing more is asked
        assertThat(pages.asked).hasSize(3)
    }

    @Test
    fun oneLoadAsksForSeveralSmallPagesUntilAScreenfulArrived() = runTest(UnconfinedTestDispatcher()) {
        // YouTube's «all» search answers pages of 2 to 11 items.
        val pages = Pages(List(10) { page -> List(4) { page * 10 + it } })
        val loader = PagedLoader(this, key = { it }, minPerLoad = 12, fetch = pages::fetch)

        loader.loadMore()

        assertThat(loader.state.value.items).hasSize(12)
        assertThat(pages.asked).hasSize(3)
        assertThat(loader.state.value.endReached).isFalse()
    }

    @Test
    fun aLoadStopsAfterTheMaximumOfRoundsEvenWithNothingNew() = runTest(UnconfinedTestDispatcher()) {
        val pages = Pages(List(20) { emptyList() })
        val loader = PagedLoader(this, key = { it }, minPerLoad = 12, maxRounds = 4, fetch = pages::fetch)

        loader.loadMore()

        assertThat(pages.asked).hasSize(4)
        assertThat(loader.state.value.items).isEmpty()
        assertThat(loader.state.value.loading).isFalse()
    }

    @Test
    fun itemsShownBeforeAreNotShownAgain() = runTest(UnconfinedTestDispatcher()) {
        val pages = Pages(listOf(listOf(1, 2, 3), listOf(3, 4, 2, 5)))
        val loader = PagedLoader(this, key = { it }, minPerLoad = 1, fetch = pages::fetch)

        loader.loadMore()
        loader.loadMore()

        assertThat(loader.state.value.items).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun aFailureKeepsWhatIsShownAndLoadMoreTriesAgain() = runTest(UnconfinedTestDispatcher()) {
        var fail = false
        val pages = Pages(listOf(listOf(1, 2), listOf(3, 4)))
        val loader = PagedLoader<Int, Int>(this, key = { it }, minPerLoad = 1) { key ->
            if (fail) throw failure(SourceErrorKind.NETWORK, "SocketTimeoutException")
            pages.fetch(key)
        }
        loader.loadMore()
        fail = true

        loader.loadMore()

        assertThat(loader.state.value.items).containsExactly(1, 2).inOrder()
        assertThat(loader.state.value.error).isEqualTo(UiError(SourceErrorKind.NETWORK, "SocketTimeoutException"))
        assertThat(loader.state.value.loading).isFalse()

        fail = false
        loader.loadMore()
        assertThat(loader.state.value.error).isNull()
        assertThat(loader.state.value.items).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun aFirstPageThatFailsIsStillAStartedList() = runTest(UnconfinedTestDispatcher()) {
        val loader = PagedLoader<Int, Int>(this, key = { it }) { throw failure() }

        loader.loadMore()

        assertThat(loader.state.value.started).isTrue()
        assertThat(loader.state.value.items).isEmpty()
        assertThat(loader.state.value.error).isNotNull()
    }

    @Test
    fun restartBeginsAgainWithAnotherSource() = runTest(UnconfinedTestDispatcher()) {
        val first = Pages(listOf(listOf(1, 2, 3)))
        val second = Pages(listOf(listOf(7, 8)))
        val loader = PagedLoader(this, key = { it }, minPerLoad = 1, fetch = first::fetch)
        loader.loadMore()

        loader.restart(second::fetch)

        assertThat(loader.state.value.items).containsExactly(7, 8).inOrder()
        assertThat(second.asked).containsExactly(null)
    }

    @Test
    fun uiErrorReadsTheKindOfASourceExceptionAndTheNameOfAnythingElse() {
        assertThat(UiError.of(failure(SourceErrorKind.BOT_CHECK, "BotCheck"))).isEqualTo(UiError(SourceErrorKind.BOT_CHECK, "BotCheck"))
        assertThat(UiError.of(java.io.IOException("x"))).isEqualTo(UiError(SourceErrorKind.NETWORK, "IOException"))
        assertThat(UiError.of(IllegalStateException("x"))).isEqualTo(UiError(null, "IllegalStateException"))
    }
}

package com.ahmadnayfeh.shasha.ui.paging

import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The loader's contract: pages one request at a time, never twice, repeats dropped, failures waiting for a retry.
 * The first block runs it one page per request ([firstScreen] 0, [nextBatch] 1: the plain behaviour); the second block is
 * what a screenful needs: pages keep coming until the first screen is full and each scroll brings a batch, whatever the
 * source's page size and the rules hide, and the list stops cleanly where the source ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagedLoaderTest {
    private val requested = mutableListOf<PageKey?>()

    /** The pages by key; a key without a page fails like a network error. One page per request. */
    private fun TestScope.loader(pages: Map<PageKey?, Page<String>>) = PagedLoader(this, { it }, firstScreen = 0, nextBatch = 1) { key ->
        requested += key
        pages[key] ?: throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null)
    }

    private val first = Page(listOf("a", "b", "c"), PageKey("1"))
    private val second = Page(listOf("c", "d"), PageKey("2")) // "c" repeats: continuations do that
    private val last = Page(listOf("e"), null)

    @Test
    fun loadsPagesInOrderAndDropsRepeats() = runTest {
        val loader = loader(mapOf(null to first, PageKey("1") to second, PageKey("2") to last))
        loader.start()
        advanceUntilIdle()
        assertThat(loader.state.value.items).containsExactly("a", "b", "c").inOrder()

        loader.loadMore()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        assertThat(loader.state.value.items).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(loader.state.value.endReached).isTrue()

        loader.loadMore() // at the end: nothing more is asked for
        advanceUntilIdle()
        assertThat(requested).containsExactly(null, PageKey("1"), PageKey("2")).inOrder()
    }

    @Test
    fun oneRequestAtATimeWhateverFocusDoes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = PagedLoader(this, { it: String -> it }, firstScreen = 0, nextBatch = 1) { key ->
            requested += key
            if (key != null) gate.await()
            if (key == null) first else last
        }
        loader.start()
        advanceUntilIdle()
        repeat(5) { loader.loadMore() } // focus sweeping over the last row
        advanceUntilIdle()
        assertThat(loader.state.value.loadingMore).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(requested).containsExactly(null, PageKey("1")).inOrder()
        assertThat(loader.state.value.loadingMore).isFalse()
    }

    @Test
    fun aFailedPageWaitsForRetryInsteadOfLooping() = runTest {
        val loader = loader(mapOf(null to first)) // page "1" fails
        loader.start()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        assertThat(loader.state.value.moreError?.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(loader.state.value.items).hasSize(3)

        repeat(3) { loader.loadMore() }
        advanceUntilIdle()
        assertThat(requested).containsExactly(null, PageKey("1")).inOrder()

        loader.retryMore()
        advanceUntilIdle()
        assertThat(requested).containsExactly(null, PageKey("1"), PageKey("1")).inOrder()
    }

    @Test
    fun aFailedFirstPageIsTheErrorScreenAndStartRetries() = runTest {
        val loader = loader(emptyMap())
        loader.start()
        advanceUntilIdle()
        assertThat(loader.state.value.error?.technicalName).isEqualTo("UnknownHostException")
        assertThat(loader.state.value.loadingFirst).isFalse()
        loader.loadMore()
        advanceUntilIdle()
        assertThat(requested).containsExactly(null)
    }

    @Test
    fun aNewSearchCancelsThePageOfTheOldOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        var query = "old"
        val loader = PagedLoader(this, { it: String -> it }, firstScreen = 0, nextBatch = 1) { key ->
            if (key != null) gate.await()
            if (key == null) Page(listOf("$query-1"), PageKey("next")) else Page(listOf("$query-2"), null)
        }
        loader.start()
        advanceUntilIdle()
        loader.loadMore() // hangs on the gate
        advanceUntilIdle()
        query = "new"
        loader.start()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(loader.state.value.items).containsExactly("new-1")
    }

    // ---- A screenful: tiny pages, pages the rules hide, the end of the results ----------------------------------------

    /** Pages of the given sizes: "1".."8", then "9".."12", ...; page n's key is "p<n>", the last page ends the list. */
    private fun pages(vararg sizes: Int): List<Page<String>> {
        var next = 1
        return sizes.mapIndexed { n, size ->
            val items = (next until next + size).map { it.toString() }
            next += size
            Page(items, if (n + 1 < sizes.size) PageKey("p${n + 1}") else null)
        }
    }

    private fun TestScope.scripted(pages: List<Page<String>>, shown: (String) -> Boolean = { true }) =
        PagedLoader(this, { it: String -> it }, shown = shown) { key ->
            requested += key
            pages[key?.id?.removePrefix("p")?.toInt() ?: 0]
        }

    @Test
    fun tinyPages_areMergedUntilTheFirstScreenIsFull() = runTest {
        // What YouTube's «all» search really gave for one query: 8, 4, 6, 2, 9, 11, 2, 11 items per page.
        val loader = scripted(pages(8, 4, 6, 2, 9, 11, 2, 11))
        loader.start()
        advanceUntilIdle()
        // 8 + 4 + 6 + 2 + 9 = 29 ≥ 24: five pages, and no sixth.
        assertThat(loader.state.value.items).hasSize(29)
        assertThat(requested).hasSize(5)
        assertThat(loader.state.value.loadingMore).isFalse()
        assertThat(loader.state.value.endReached).isFalse()
        assertThat(loader.nextPage).isEqualTo(PageKey("p5"))
    }

    @Test
    fun theFirstPageShowsAtOnce_whileTheScreenFillsBehindIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val all = pages(8, 30)
        val loader = PagedLoader(this, { it: String -> it }) { key ->
            requested += key
            if (key != null) gate.await()
            all[key?.id?.removePrefix("p")?.toInt() ?: 0]
        }
        loader.start()
        advanceUntilIdle()
        // Page 2 is still on its way: page 1 is on screen with the loader under it.
        assertThat(loader.state.value.items).hasSize(8)
        assertThat(loader.state.value.loadingFirst).isFalse()
        assertThat(loader.state.value.loadingMore).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(loader.state.value.items).hasSize(38)
        assertThat(loader.state.value.loadingMore).isFalse()
    }

    @Test
    fun aFullFirstPage_asksForNothingMore() = runTest {
        val loader = scripted(pages(30, 30))
        loader.start()
        advanceUntilIdle()
        assertThat(requested).containsExactly(null)
        assertThat(loader.state.value.items).hasSize(30)
        assertThat(loader.state.value.loadingMore).isFalse()
    }

    @Test
    fun whatTheRulesHide_countsForNothing_soTheScreenStillFills() = runTest {
        // Pages of 20 where only every fourth item is shown (a profile hiding Shorts): 5 shown per page.
        val loader = scripted(pages(20, 20, 20, 20, 20, 20, 20, 20)) { it.toInt() % 4 == 0 }
        loader.start()
        advanceUntilIdle()
        // Five pages give 25 shown items ≥ 24.
        assertThat(requested).hasSize(5)
        assertThat(loader.state.value.items.count { it.toInt() % 4 == 0 }).isEqualTo(25)
    }

    @Test
    fun eachScrollBringsABatch_notJustOnePage() = runTest {
        val loader = scripted(pages(30, 5, 5, 5, 5, 5, 5, 5, 5))
        loader.start()
        advanceUntilIdle()
        assertThat(requested).hasSize(1)

        loader.loadMore()
        advanceUntilIdle()
        // 5 + 5 + 5 = 15 ≥ 12 new items: three pages, then it rests.
        assertThat(requested).hasSize(4)
        assertThat(loader.state.value.items).hasSize(45)
        assertThat(loader.state.value.loadingMore).isFalse()

        loader.loadMore()
        advanceUntilIdle()
        assertThat(requested).hasSize(7)
    }

    @Test
    fun aRunWhosePagesAreAllHidden_looksFurtherButNotForever() = runTest {
        val loader = scripted(pages(*IntArray(40) { 10 })) { false }
        loader.start()
        advanceUntilIdle()
        // The first page plus one run of PAGES_WITHOUT_ITEMS more.
        assertThat(requested).hasSize(1 + PagedLoader.PAGES_WITHOUT_ITEMS)
        assertThat(loader.state.value.loadingMore).isFalse()
        assertThat(loader.state.value.endReached).isFalse()
        assertThat(loader.state.value.moreError).isNull()

        // Asking again (the user scrolls) goes on from there, one more run.
        loader.loadMore()
        advanceUntilIdle()
        assertThat(requested).hasSize(1 + 2 * PagedLoader.PAGES_WITHOUT_ITEMS)
    }

    @Test
    fun aPageThatIsOnlyRepeats_addsNothingAndTheRunLooksOneFurther() = runTest {
        val all = listOf(
            Page(List(30) { "a$it" }, PageKey("p1")),
            Page(List(30) { "a$it" }, PageKey("p2")), // the same 30 again
            Page(List(14) { "b$it" }, null),
        )
        val loader = PagedLoader(this, { it: String -> it }) { key ->
            requested += key
            all[key?.id?.removePrefix("p")?.toInt() ?: 0]
        }
        loader.start()
        advanceUntilIdle()
        assertThat(requested).containsExactly(null) // 30 ≥ 24: the screen is full
        loader.loadMore()
        advanceUntilIdle()
        assertThat(loader.state.value.items).hasSize(44)
        assertThat(loader.state.value.endReached).isTrue()
    }

    @Test
    fun theEndOfTheResults_stopsCleanly() = runTest {
        val loader = scripted(pages(8, 4, 6)) // 18 items in all: never reaches 24
        loader.start()
        advanceUntilIdle()
        assertThat(loader.state.value.items).hasSize(18)
        assertThat(loader.state.value.endReached).isTrue()
        assertThat(loader.state.value.loadingMore).isFalse()
        assertThat(loader.nextPage).isNull()
        assertThat(requested).hasSize(3)

        repeat(5) { loader.loadMore() } // focus at the end of the list again and again
        advanceUntilIdle()
        assertThat(requested).hasSize(3)
        assertThat(loader.state.value.loadingMore).isFalse()
    }

    @Test
    fun aPageThatFailsWhileTheScreenFills_keepsWhatWasLoadedAndWaitsForRetry() = runTest {
        val all = pages(8, 4, 6, 10)
        var failing = true
        val loader = PagedLoader(this, { it: String -> it }) { key ->
            requested += key
            val n = key?.id?.removePrefix("p")?.toInt() ?: 0
            if (n == 2 && failing) throw SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null)
            all[n]
        }
        loader.start()
        advanceUntilIdle()
        assertThat(loader.state.value.items).hasSize(12) // pages 0 and 1
        assertThat(loader.state.value.error).isNull() // the first page worked: no error screen
        assertThat(loader.state.value.moreError?.technicalName).isEqualTo("SocketTimeoutException")
        assertThat(loader.state.value.loadingMore).isFalse()

        repeat(3) { loader.loadMore() }
        advanceUntilIdle()
        assertThat(requested).hasSize(3) // not asked again by itself

        failing = false
        loader.retryMore()
        advanceUntilIdle()
        assertThat(loader.state.value.moreError).isNull()
        assertThat(loader.state.value.items).hasSize(28) // 8 + 4 + 6 + 10
        assertThat(loader.state.value.endReached).isTrue()
    }

    @Test
    fun aNewStartCancelsTheFillOfTheOldOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        var query = "old"
        val loader = PagedLoader(this, { it: String -> it }) { key ->
            if (key != null) gate.await()
            if (key == null) Page(listOf("$query-1"), PageKey("next")) else Page(List(30) { "$query-more-$it" }, null)
        }
        loader.start()
        advanceUntilIdle() // the fill hangs on the gate
        query = "new"
        loader.start()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(loader.state.value.items.none { it.startsWith("old") }).isTrue()
        assertThat(loader.state.value.items.first()).isEqualTo("new-1")
    }
}

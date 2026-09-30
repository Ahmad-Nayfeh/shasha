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

@OptIn(ExperimentalCoroutinesApi::class)
class PagedLoaderTest {
    private val requested = mutableListOf<PageKey?>()

    /** The pages by key; a key without a page fails like a network error. */
    private fun TestScope.loader(pages: Map<PageKey?, Page<String>>) = PagedLoader(this, { it }) { key ->
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
        val loader = PagedLoader(this, { it: String -> it }) { key ->
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
        val loader = PagedLoader(this, { it: String -> it }) { key ->
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
}

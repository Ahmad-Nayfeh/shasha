package com.ahmadnayfeh.shasha.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamPrefetcherTest {
    @Test
    fun focusAlone_looksNothingUp() = runTest {
        // A lookup per focused card made YouTube ask for a bot check; only a click looks up now.
        val done = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope) { done += it }
        p.onFocus("a")
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(done).isEmpty()
        p.onClick("a")
        runCurrent()
        assertThat(done).containsExactly("a")
    }

    @Test
    fun afterABotCheck_noLookupInAdvanceForAWhile() = runTest {
        var now = 0L
        val done = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope, now = { now }) { id ->
            done += id
            if (id == "a") throw com.ahmadnayfeh.shasha.youtube.SourceException(com.ahmadnayfeh.shasha.youtube.SourceErrorKind.BOT_CHECK, "test", "bot", null)
        }
        p.onClick("a")
        runCurrent()
        p.onClick("b")
        runCurrent()
        assertThat(done).containsExactly("a")
        now = StreamPrefetcher.PAUSE_AFTER_THROTTLE_MS + 1
        p.onClick("b")
        runCurrent()
        assertThat(done).containsExactly("a", "b").inOrder()
    }

    @Test
    fun leavingTheCard_dropsThePendingResolve() = runTest {
        val done = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope) { done += it }
        p.onFocus("a")
        advanceTimeBy(200)
        p.onBlur()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(done).isEmpty()
    }

    @Test
    fun aClick_resolvesAtOnce() = runTest {
        val done = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope) { done += it }
        p.onFocus("a")
        p.onClick("a")
        runCurrent()
        assertThat(done).containsExactly("a")
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(done).containsExactly("a") // the focus debounce was replaced by the click
    }

    @Test
    fun atMostTwoAreInFlight_andAFailureIsNothing() = runTest {
        val gates = HashMap<String, CompletableDeferred<Unit>>()
        val started = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope) {
            started += it
            gates.getOrPut(it) { CompletableDeferred() }.await()
            if (it == "a") error("boom")
        }
        listOf("a", "b", "c").forEach { p.onClick(it) }
        runCurrent()
        assertThat(started).containsExactly("a", "b").inOrder()
        gates.getValue("a").complete(Unit)
        runCurrent()
        assertThat(started).containsExactly("a", "b", "c").inOrder()
    }
}

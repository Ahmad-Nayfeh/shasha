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
    fun aCardThatKeepsTheFocus_isResolved_aCardPassedOver_isNot() = runTest {
        val done = mutableListOf<String>()
        val p = StreamPrefetcher(backgroundScope) { done += it }
        p.onFocus("a")
        advanceTimeBy(300)
        p.onFocus("b") // the focus moved before 400 ms
        advanceTimeBy(399)
        runCurrent()
        assertThat(done).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(done).containsExactly("b")
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

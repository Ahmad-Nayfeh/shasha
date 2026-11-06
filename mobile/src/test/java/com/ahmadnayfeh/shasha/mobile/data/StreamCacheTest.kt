package com.ahmadnayfeh.shasha.mobile.data

import com.ahmadnayfeh.shasha.mobile.TestStreams
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsQueue
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private var now = 0L
    private val asked = ArrayList<String>()
    private var gate: CompletableDeferred<Unit>? = null
    private var failNext = false

    private val cache = StreamCache(scope, { now }, ttlMs = 1000, maxEntries = 3, maxPrefetch = 2) { id ->
        asked += id
        gate?.await()
        if (failNext) {
            failNext = false
            error("boom")
        }
        TestStreams.details(video(id))
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aSecondGetIsAHit() = runTest {
        cache.get("a")
        cache.get("a")
        assertThat(asked).containsExactly("a")
    }

    @Test
    fun anEntryExpiresAfterTheTtl() = runTest {
        cache.get("a")
        now = 1001
        cache.get("a")
        assertThat(asked).containsExactly("a", "a")
    }

    @Test
    fun concurrentRequestsShareOneLoad() = runTest {
        gate = CompletableDeferred()
        val first = async { cache.get("a") }
        val second = async { cache.get("a") }
        cache.prefetch("a")
        gate!!.complete(Unit)
        assertThat(first.await()).isSameInstanceAs(second.await())
        assertThat(asked).containsExactly("a")
    }

    @Test
    fun aFailureIsNotKept() = runTest {
        failNext = true
        runCatching { cache.get("a") }
        cache.get("a")
        assertThat(asked).containsExactly("a", "a")
    }

    @Test
    fun theLeastRecentlyUsedEntryGoesFirst() = runTest {
        listOf("a", "b", "c").forEach { cache.get(it) }
        cache.get("a") // a is the freshest now
        cache.get("d") // b goes
        asked.clear()
        cache.get("a")
        cache.get("b")
        assertThat(asked).containsExactly("b")
    }

    @Test
    fun atMostTwoPrefetchesRunAtOnce_theRestAreDropped() = runTest {
        gate = CompletableDeferred()
        cache.prefetch("a")
        cache.prefetch("b")
        cache.prefetch("c")
        assertThat(asked).containsExactly("a", "b")
        gate!!.complete(Unit)
        cache.prefetch("c")
        assertThat(asked).containsExactly("a", "b", "c")
    }

    @Test
    fun aCancelledPrefetchIsLookedUpAgainOnDemand() = runTest {
        gate = CompletableDeferred()
        cache.prefetch("a")
        cache.cancelPrefetch("a")
        gate!!.complete(Unit)
        cache.get("a")
        assertThat(asked).containsExactly("a", "a")
    }

    @Test
    fun theShortsWindowIsTheOneAfterTheCurrentOne() {
        // One, not two: more lookups ahead made YouTube ask for a bot check.
        val queue = ShortsQueue.of(listOf("a", "b", "c", "d").map { video(it) }, "a")
        assertThat(queue.ahead(0).map { it.videoId }).containsExactly("b")
        assertThat(queue.ahead(2).map { it.videoId }).containsExactly("d")
        assertThat(queue.ahead(3)).isEmpty()
        assertThat(queue.ahead(-1)).isEmpty()
    }
}

package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.policy.clip
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The lists the Shorts player flips through: kept by key (the route carries only the key), each Short once, the newest few. */
class ShortsQueuesTest {
    private fun shorts(vararg ids: String) = ids.map { clip(it, short = true) }

    @Test
    fun aListComesBackByItsKey_inTheOrderItWasPut() {
        val queues = ShortsQueues()
        val key = queues.put(shorts("a0000000001", "b0000000001", "c0000000001"))
        assertThat(queues.get(key)!!.map { it.videoId }).containsExactly("a0000000001", "b0000000001", "c0000000001").inOrder()
    }

    @Test
    fun eachShortIsInItsListOnce() {
        val queues = ShortsQueues()
        val key = queues.put(shorts("a0000000001", "b0000000001", "a0000000001", "c0000000001", "b0000000001"))
        assertThat(queues.get(key)!!.map { it.videoId }).containsExactly("a0000000001", "b0000000001", "c0000000001").inOrder()
    }

    @Test
    fun everyListHasAKeyOfItsOwn_andAnUnknownKeyIsNothing() {
        val queues = ShortsQueues()
        val one = queues.put(shorts("a0000000001"))
        val two = queues.put(shorts("a0000000001"))
        assertThat(one).isNotEqualTo(two)
        assertThat(queues.get("no-such-key")).isNull()
    }

    @Test
    fun onlyTheNewestFewListsAreKept_andOneStillInUseIsNotTheFirstToGo() {
        val queues = ShortsQueues(max = 3)
        val first = queues.put(shorts("a0000000001"))
        val second = queues.put(shorts("b0000000001"))
        val third = queues.put(shorts("c0000000001"))
        // The first is looked at again (the Shorts player came back to it): the second is now the one that has been left longest.
        assertThat(queues.get(first)).isNotNull()
        val fourth = queues.put(shorts("d0000000001"))
        assertThat(queues.get(second)).isNull()
        assertThat(queues.get(first)).isNotNull()
        assertThat(queues.get(third)).isNotNull()
        assertThat(queues.get(fourth)).isNotNull()
    }
}

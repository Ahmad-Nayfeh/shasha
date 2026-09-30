package com.ahmadnayfeh.shasha.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The stream cache: a whole extraction per entry, so only a few and only briefly. */
class ExpiringCacheTest {
    private var now = 0L
    private val cache = ExpiringCache<String, String>(maxEntries = 3, maxAgeMs = 10_000, clock = { now })

    @Test
    fun theLeastRecentlyUsedEntryLeavesFirst() {
        cache["a"] = "A"
        cache["b"] = "B"
        cache["c"] = "C"
        assertThat(cache["a"]).isEqualTo("A") // now "b" is the least recently used
        cache["d"] = "D"
        assertThat(cache.size).isEqualTo(3)
        assertThat(cache["b"]).isNull()
        assertThat(cache["a"]).isEqualTo("A")
    }

    @Test
    fun expiredEntriesAreDroppedOnAnyAccess_notOnlyWhenAskedFor() {
        cache["old"] = "1"
        now = 6_000
        cache["newer"] = "2"
        now = 10_001
        assertThat(cache["newer"]).isEqualTo("2")
        assertThat(cache.size).isEqualTo(1) // "old" is gone although nobody asked for it
        now = 16_001
        assertThat(cache["newer"]).isNull()
        assertThat(cache.size).isEqualTo(0)
    }

    @Test
    fun removeDropsAnEntry() {
        cache["a"] = "A"
        cache.remove("a")
        assertThat(cache["a"]).isNull()
    }
}

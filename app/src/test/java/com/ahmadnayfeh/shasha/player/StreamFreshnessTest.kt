package com.ahmadnayfeh.shasha.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamFreshnessTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val wallNow = 1_790_634_251_000L

    /** An extraction that arrived at elapsed time [at], its URLs valid for [validFor] (YouTube: about 6 h). */
    private fun fresh(at: Long = 0, validFor: Long? = 6 * hour) = StreamFreshness(at, validFor?.let { wallNow + it }, wallNow)

    @Test
    fun youngExtractionsAreNeverReplaced() {
        val f = fresh()
        assertThat(f.refreshBeforeStart(0)).isFalse()
        assertThat(f.refreshBeforeStart(9 * minute)).isFalse()
        // A 403 right away is YouTube refusing: straight to the error screen.
        assertThat(f.refreshAfterRefusal(0)).isFalse()
        assertThat(f.refreshAfterRefusal(9 * minute)).isFalse()
    }

    @Test
    fun aPlayerRestartedCloseToExpiryGetsFreshUrlsFirst() {
        val f = fresh(at = 1_000)
        assertThat(f.refreshBeforeStart(1_000 + 20 * minute)).isFalse() // back from Home after 20 min
        assertThat(f.refreshBeforeStart(1_000 + 4 * hour)).isFalse() // 2 h left
        assertThat(f.refreshBeforeStart(1_000 + 5 * hour)).isTrue() // 1 h left
        assertThat(f.refreshBeforeStart(1_000 + 30 * hour)).isTrue() // resumed the next day
    }

    @Test
    fun a403OnUrlsInUseForAWhileGetsOneFreshExtraction() {
        val f = fresh()
        assertThat(f.refreshAfterRefusal(10 * minute)).isTrue() // e.g. the TV's public address changed
        assertThat(f.refreshAfterRefusal(7 * hour)).isTrue() // expired: paused for hours, or a long live stream
    }

    @Test
    fun unknownOrImplausibleExpiryUsesSafeLifetimes() {
        val unknown = fresh(validFor = null) // 5 h assumed
        assertThat(unknown.refreshBeforeStart(3 * hour + 59 * minute)).isFalse()
        assertThat(unknown.refreshBeforeStart(4 * hour)).isTrue()

        // The TV's clock runs hours ahead, so the URLs look expired on arrival: still no refresh loop.
        val clockAhead = fresh(validFor = -2 * hour)
        assertThat(clockAhead.refreshBeforeStart(0)).isFalse()
        assertThat(clockAhead.refreshBeforeStart(9 * minute)).isFalse()
        assertThat(clockAhead.refreshBeforeStart(10 * minute)).isTrue()

        // Clock far behind: at most 24 h.
        val clockBehind = fresh(validFor = 72 * hour)
        assertThat(clockBehind.refreshBeforeStart(22 * hour)).isFalse()
        assertThat(clockBehind.refreshBeforeStart(23 * hour)).isTrue()
    }

    @Test
    fun ageNeverGoesNegative() {
        assertThat(fresh(at = 5_000).ageMs(1_000)).isEqualTo(0)
        assertThat(fresh(at = 5_000).ageMs(65_000)).isEqualTo(60_000)
    }
}

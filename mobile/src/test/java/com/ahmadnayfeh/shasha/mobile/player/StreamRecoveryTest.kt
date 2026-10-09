package com.ahmadnayfeh.shasha.mobile.player

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When a stream that stopped working is extracted again by itself, and when the failure is the viewer's to see. */
class StreamRecoveryTest {
    private var now = 1_000_000L
    private val recovery = StreamRecovery(clock = { now })

    @Test
    fun expiredAddressesAndBrokenConnectionsAreTriedAgain_aFewTimes_laterOnesAfterAPause() {
        assertThat(recovery.next(SourceErrorKind.BLOCKED_403)).isEqualTo(0L)
        assertThat(recovery.next(SourceErrorKind.NETWORK)).isEqualTo(2_000L)
        assertThat(recovery.next(SourceErrorKind.NETWORK)).isEqualTo(5_000L)
        assertThat(recovery.next(SourceErrorKind.NETWORK)).isNull()
    }

    @Test
    fun otherFailuresAreNeverTriedAgain() {
        for (kind in listOf(SourceErrorKind.RATE_LIMITED, SourceErrorKind.DEVICE_DECODER, SourceErrorKind.EXTRACTION_BROKEN, SourceErrorKind.CONTENT_UNAVAILABLE)) {
            assertThat(recovery.next(kind)).isNull()
        }
        // …and they used none of the tries.
        assertThat(recovery.next(SourceErrorKind.BLOCKED_403)).isEqualTo(0L)
    }

    @Test
    fun aVideoThatPlayedOnForAWhileHasAllItsTriesAgain() {
        repeat(StreamRecovery.MAX_ATTEMPTS) { recovery.next(SourceErrorKind.NETWORK) }
        assertThat(recovery.next(SourceErrorKind.NETWORK)).isNull()

        now += StreamRecovery.FORGET_AFTER_MS

        assertThat(recovery.next(SourceErrorKind.BLOCKED_403)).isEqualTo(0L)
    }

    @Test
    fun aNewVideoStartsAfresh() {
        repeat(StreamRecovery.MAX_ATTEMPTS) { recovery.next(SourceErrorKind.NETWORK) }

        recovery.reset()

        assertThat(recovery.next(SourceErrorKind.NETWORK)).isEqualTo(0L)
    }
}

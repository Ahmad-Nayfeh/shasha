package com.ahmadnayfeh.shasha.ui.screens.home

import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What the home screen says about the feed sync: waiting, failing (and why), or nothing. */
class HomeSyncTest {
    private val botCheck = SourceException(SourceErrorKind.BOT_CHECK, "SignInConfirmNotBotException", null, null)

    private fun failed(failed: Int, of: Int) = SyncHealth(channels = of, attempted = of, failed = failed, lastAttemptAtMs = 1, lastError = botCheck)

    @Test
    fun everyChannelFailed_isTheError_notAnEndlessWait() {
        val sync = HomeSync(health = failed(5, of = 5))
        assertThat(sync.busy).isFalse()
        assertThat(sync.failure).isSameInstanceAs(botCheck)
    }

    @Test
    fun channelsNeverSynced_waitWhenOnline_andSaySoWhenOffline() {
        val imported = SyncHealth(channels = 20)
        assertThat(HomeSync(online = true, health = imported).busy).isTrue()
        assertThat(HomeSync(online = true, health = imported).failure).isNull()
        val offline = HomeSync(online = false, health = imported)
        assertThat(offline.busy).isFalse()
        assertThat(offline.failure?.kind).isEqualTo(SourceErrorKind.NETWORK)
    }

    @Test
    fun aFewDeadChannels_areNotAFailure_mostFailingIs() {
        assertThat(HomeSync(health = failed(3, of = 100)).failure).isNull()
        assertThat(HomeSync(health = failed(99, of = 100)).failure).isSameInstanceAs(botCheck)
        // Offline: the failing run is explained by the missing network.
        assertThat(HomeSync(online = false, health = failed(99, of = 100)).failure?.kind).isEqualTo(SourceErrorKind.NETWORK)
        // Offline with a healthy feed: nothing to report until a run fails.
        assertThat(HomeSync(online = false, health = failed(0, of = 100)).failure).isNull()
    }

    @Test
    fun whileARunGoes_nothingIsReported() {
        val sync = HomeSync(running = true, health = failed(5, of = 5))
        assertThat(sync.busy).isTrue()
        assertThat(sync.failure).isNull()
        // Not read from the database yet: waiting, not failing.
        assertThat(HomeSync().busy).isTrue()
        assertThat(HomeSync().failure).isNull()
    }
}

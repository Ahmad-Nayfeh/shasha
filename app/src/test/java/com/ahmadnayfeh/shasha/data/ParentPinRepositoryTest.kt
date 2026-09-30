package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.policy.MonotonicClock
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinAttemptLimiter
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.instantAt
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ParentPinRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var stores: TestDataStores
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))

    /** Few iterations: the tests check the logic, not PBKDF2's cost. */
    private fun repo(iterations: Int = 1_000, store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = stores.open()) =
        ParentPinRepository(store, clock, PinHasher(iterations = iterations))

    @Before
    fun setUp() {
        stores = TestDataStores(tmp.root)
    }

    @After
    fun tearDown() = stores.closeAll()

    @Test
    fun firstStart_hasNoPin_untilItIsSetUpOnce() = runBlocking<Unit> {
        val pin = repo()
        assertThat(pin.isSet()).isFalse()
        assertThat(pin.isSet.first()).isFalse()
        assertThat(pin.verify("1234")).isEqualTo(PinResult.NotSet)

        assertThat(pin.setup("12")).isEqualTo(PinSetupResult.InvalidFormat)
        assertThat(pin.setup("12a4")).isEqualTo(PinSetupResult.InvalidFormat)
        assertThat(pin.setup("2468")).isEqualTo(PinSetupResult.Done)
        assertThat(pin.isSet()).isTrue()
        assertThat(pin.setup("1357")).isEqualTo(PinSetupResult.AlreadySet)
        assertThat(pin.verify("2468")).isEqualTo(PinResult.Success)
        assertThat(pin.verify("1357")).isEqualTo(PinResult.Wrong(PinAttemptLimiter.MAX_FAILURES - 1))
    }

    @Test
    fun fiveWrongPins_lockEveryCheck_evenTheRightPin_andARestartKeepsTheLock() = runBlocking<Unit> {
        val pin = repo()
        pin.setup("2468")
        val results = (1..5).map { pin.verify("0000") }
        assertThat(results.take(4)).containsExactly(PinResult.Wrong(4), PinResult.Wrong(3), PinResult.Wrong(2), PinResult.Wrong(1)).inOrder()
        assertThat(results.last()).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        assertThat(pin.verify("2468")).isInstanceOf(PinResult.LockedOut::class.java)

        // A new process (the app was killed to reset the count): still locked.
        clock.advanceMillis(60_000)
        val afterRestart = repo(store = stores.restart())
        assertThat(afterRestart.verify("2468")).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS - 60_000))
        assertThat(afterRestart.remainingLockMillis()).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS - 60_000)

        clock.advanceMillis(PinAttemptLimiter.LOCKOUT_MILLIS)
        assertThat(afterRestart.verify("2468")).isEqualTo(PinResult.Success)
        assertThat(afterRestart.verify("0000")).isEqualTo(PinResult.Wrong(4)) // a fresh set of tries
    }

    /** The TV's uptime ([com.ahmadnayfeh.shasha.policy.DeviceUptime] on the TV). */
    private class Uptime(var elapsed: Long) : MonotonicClock {
        override fun elapsedMillis(): Long = elapsed
        override val bootId: Long = 12
    }

    @Test
    fun withTheTvsUptime_settingTheClockForward_doesNotEndTheLockout_andTheNextOneIsLonger() = runBlocking<Unit> {
        val uptime = Uptime(elapsed = 3_600_000L)
        val pin = ParentPinRepository(stores.open(), clock, PinHasher(iterations = 1_000), uptime = uptime)
        pin.setup("2468")
        repeat(5) { pin.verify("0000") }

        // A child sets the TV's clock a day ahead: still locked, even after the app restarts.
        clock.advanceMillis(24 * HOUR_MS)
        assertThat(pin.verify("2468")).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        val restarted = ParentPinRepository(stores.restart(), clock, PinHasher(iterations = 1_000), uptime = uptime)
        assertThat(restarted.remainingLockMillis()).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS)

        // Five more wrong PINs after it: a longer lockout.
        uptime.elapsed += PinAttemptLimiter.LOCKOUT_MILLIS
        repeat(4) { restarted.verify("0000") }
        assertThat(restarted.verify("0000")).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.lockoutMillis(2)))
    }

    @Test
    fun wrongCountsSurviveARestart_too() = runBlocking<Unit> {
        repo().apply {
            setup("2468")
            verify("0000")
            verify("0000")
        }
        assertThat(repo(store = stores.restart()).verify("0000")).isEqualTo(PinResult.Wrong(2))
    }

    @Test
    fun theTvAndThePhoneAtOnce_shareOneCount() = runBlocking<Unit> {
        val pin = repo()
        pin.setup("2468")
        val results = (1..10).map { async { pin.verify("1111") } }.awaitAll()
        assertThat(results.count { it is PinResult.Wrong }).isEqualTo(PinAttemptLimiter.MAX_FAILURES - 1)
        assertThat(results.count { it is PinResult.LockedOut }).isEqualTo(10 - (PinAttemptLimiter.MAX_FAILURES - 1))
    }

    @Test
    fun change_needsTheCurrentPin_andAnnouncesTheChange() = runBlocking<Unit> {
        val pin = repo()
        val changes = mutableListOf<Unit>()
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { pin.changes.take(2).toList(changes) }
        pin.setup("2468")

        assertThat(pin.change("0000", "1357")).isEqualTo(PinResult.Wrong(4))
        assertThat(pin.verify("2468")).isEqualTo(PinResult.Success)
        assertThat(pin.change("2468", "1357")).isEqualTo(PinResult.Success)
        assertThat(pin.verify("2468")).isEqualTo(PinResult.Wrong(4))
        assertThat(pin.verify("1357")).isEqualTo(PinResult.Success)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { pin.change("1357", "12") } }

        withTimeout(5_000) { watching.join() }
        assertThat(changes).hasSize(2) // set up, changed
    }

    @Test
    fun anOlderHash_isUpgradedWhenTheRightPinIsTyped() = runBlocking<Unit> {
        val store = stores.open()
        ParentPinRepository(store, clock, PinHasher(iterations = 500)).setup("2468")
        val stronger = PinHasher(iterations = 1_000)
        val pin = ParentPinRepository(store, clock, stronger)

        assertThat(pin.verify("2468")).isEqualTo(PinResult.Success)
        val stored = store.data.first()[PolicyStore.PARENT_PIN_HASH]!!
        assertThat(stronger.needsRehash(stored)).isFalse()
        assertThat(pin.verify("2468")).isEqualTo(PinResult.Success)
    }

    @Test
    fun aDamagedLockoutRecord_startsCountingAgain() = runBlocking<Unit> {
        val store = stores.open()
        store.edit { it[PolicyStore.PIN_ATTEMPTS] = "{broken" }
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        pin.setup("2468")
        assertThat(pin.verify("0000")).isEqualTo(PinResult.Wrong(4))
    }
}

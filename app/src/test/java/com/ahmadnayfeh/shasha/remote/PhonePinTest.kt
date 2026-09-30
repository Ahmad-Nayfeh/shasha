package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinAttemptLimiter
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.instantAt
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The parent PIN as the phone checks it ([PhonePin]): its own count, saved like the TV's, so neither a restart
 * of the app nor a reboot of the TV grants new guesses, and never the TV's (owner decision). [PIN] and
 * [WRONG_PIN] are RemoteTestEnv's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhonePinTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var stores: TestDataStores
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val minute = 60_000L

    @Before
    fun setUp() {
        stores = TestDataStores(tmp.root)
    }

    @After
    fun tearDown() = stores.closeAll()

    /** The TV's PIN pad over [store], with the parent PIN set up. */
    private suspend fun tvPad(store: DataStore<Preferences>) =
        ParentPinRepository(store, clock, PinHasher(iterations = 1_000)).also { it.setup(PIN) }

    private suspend fun PhonePin.wrongFiveTimes(): List<PinResult> = (1..5).map { check(WRONG_PIN) }

    @Test
    fun beforeTheFirstStartsSetup_thereIsNoPin() = runBlocking<Unit> {
        assertThat(PhonePin(stores.open(), clock).check(PIN)).isEqualTo(PinResult.NotSet)
    }

    @Test
    fun fiveWrongPins_closeThePhone_andReopeningTheStoreKeepsItClosed_whileTheTvPadStaysOpen() = runBlocking<Unit> {
        val store = stores.open()
        val tv = tvPad(store)
        val phone = PhonePin(store, clock)
        val results = phone.wrongFiveTimes()
        assertThat(results.take(4)).containsExactly(PinResult.Wrong(4), PinResult.Wrong(3), PinResult.Wrong(2), PinResult.Wrong(1)).inOrder()
        assertThat(results.last()).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        assertThat(phone.check(PIN)).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        // Owner decision: the phone's guesses never close the TV's PIN pad (nor the TV's the phone's).
        assertThat(tv.verify(PIN)).isEqualTo(PinResult.Success)

        // A new process: the app was killed, updated, or the TV unplugged.
        clock.advanceMillis(minute)
        val again = PhonePin(stores.restart(), clock)
        assertThat(again.check(PIN)).isEqualTo(PinResult.LockedOut(4 * minute))
        assertThat(again.status()).isEqualTo(PhonePinStatus(wrong = 5, lockedMillis = 4 * minute))

        // Guessing on after the lockout: the next one lasts longer, as on the TV (15 min).
        clock.advanceMillis(4 * minute)
        assertThat(again.wrongFiveTimes().last()).isEqualTo(PinResult.LockedOut(PinAttemptLimiter.lockoutMillis(2)))
        assertThat(again.status().wrong).isEqualTo(10)
    }

    @Test
    fun theRightPin_forgetsTheCount() = runBlocking<Unit> {
        val store = stores.open()
        tvPad(store)
        val phone = PhonePin(store, clock)
        repeat(3) { phone.check(WRONG_PIN) }
        assertThat(phone.status()).isEqualTo(PhonePinStatus(wrong = 3, lockedMillis = 0))
        assertThat(phone.check(PIN)).isEqualTo(PinResult.Success)
        assertThat(phone.status()).isEqualTo(PhonePinStatus(wrong = 0, lockedMillis = 0))
        assertThat(phone.check(WRONG_PIN)).isEqualTo(PinResult.Wrong(4))
    }

    @Test
    fun aNewPairingToken_forgetsTheCount_forGood() = runBlocking<Unit> {
        val store = stores.open()
        tvPad(store)
        val phone = PhonePin(store, clock)
        phone.wrongFiveTimes()
        assertThat(phone.remainingLockMillis()).isEqualTo(PinAttemptLimiter.LOCKOUT_MILLIS)

        phone.forget()
        assertThat(phone.remainingLockMillis()).isEqualTo(0L)
        assertThat(PhonePin(stores.restart(), clock).status()).isEqualTo(PhonePinStatus(wrong = 0, lockedMillis = 0))
    }
}

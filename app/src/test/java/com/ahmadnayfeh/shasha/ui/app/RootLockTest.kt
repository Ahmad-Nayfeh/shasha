package com.ahmadnayfeh.shasha.ui.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.epochMsAt
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §4 at start: a child's locked video goes on after a restart without the profile picker in between
 * (anyone may be in front of the TV at start, but this TV was left locked on that video), while the adult's
 * locked video still waits behind the picker's PIN. And the lock follows a PIN profile switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RootLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private val viewModels = ViewModelStore()

    private lateinit var profiles: ProfileRepository
    private lateinit var active: ActiveProfile
    private lateinit var gate: PolicyGate
    private lateinit var lock: LockController
    private lateinit var deps: RootDeps
    private lateinit var usage: UsageRepository

    private val adult = owner()
    private val child = kid()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        profiles = ProfileRepository(db)
        profiles.ensureOwner(adult.name)
        profiles.upsert(child)
        active = ActiveProfile.create(profiles, store, scope, ownerName = adult.name)
        usage = UsageRepository(db)
        val session = SessionController(engine, active.profile, usage, OverridesRepository(store), scope)
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        pin.setup(PIN)
        val locks = PlaybackLockStore(store, scope)
        gate = PolicyGate(engine, active, profiles, session, locks, pin, VideoLookup { LookedUpVideo(clip(it), null) }, scope, surrenders = usage::recordSurrender)
        lock = LockController(engine, locks, active.profile, gate::verifyParentPin, pin::remainingLockMillis, usage::recordSurrender, scope)
        deps = RootDeps(profiles, active, pin, gate, session, lock = lock)
        active.awaitProfile()
        Unit
    }

    @After
    fun tearDown() {
        viewModels.clear()
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
        Dispatchers.resetMain()
    }

    private fun root(): RootViewModel =
        ViewModelProvider(viewModels, viewModelFactory { initializer { RootViewModel({ deps }) } })[RootViewModel::class.java]

    private fun RootViewModel.shows(screen: RootScreen) = runBlocking { withTimeout(5_000) { this@shows.screen.first { it == screen } } }

    @Test
    fun aChildsLockedVideo_skipsThePickerAtStart() = runBlocking<Unit> {
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        lock.onVideoStarted(child.id, "kidvideo001", null)
        root().shows(RootScreen.Browse)
    }

    @Test
    fun withoutALock_orWithTheAdultsLock_thePickerAsksAsUsual() = runBlocking<Unit> {
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        root().shows(RootScreen.Picker(PickerMode.START))
        viewModels.clear()

        assertThat(gate.switchProfile(adult.id, PIN)).isEqualTo(SwitchResult.Switched)
        lock.onVideoStarted(adult.id, "adultvideo1", null)
        root().shows(RootScreen.Picker(PickerMode.START))
    }

    @Test
    fun anAdultsLockLeftBehind_neverKeepsAChildFromTheTv_itEndsAsASurrender() = runBlocking<Unit> {
        // The adult switched the TV off in the middle of a locked video; later a child picks their card.
        lock.onVideoStarted(adult.id, "adultvideo1", null)
        withTimeout(5_000) { lock.lock.first { it != null } }
        clock.advanceMillis(3_600_000)
        val root = root()
        root.shows(RootScreen.Picker(PickerMode.START))
        assertThat(root.needsPin(child)).isFalse()
        assertThat(root.choose(child, pin = null)).isEqualTo(PinCheck.Accepted)
        root.shows(RootScreen.Browse)
        assertThat(active.awaitProfile().id).isEqualTo(child.id)
        withTimeout(5_000) { lock.lock.first { it == null } }
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders(adult.id, 0)).containsExactly(SurrenderEvent(adult.id, epochMsAt("2026-09-28T13:00"), "adultvideo1"))
    }

    @Test
    fun theAdultPickingHimselfAtTheStart_findsHisLockedVideoStillLocked() = runBlocking<Unit> {
        lock.onVideoStarted(adult.id, "adultvideo1", null)
        withTimeout(5_000) { lock.lock.first { it != null } }
        val root = root()
        root.shows(RootScreen.Picker(PickerMode.START))
        assertThat(root.choose(adult, PIN)).isEqualTo(PinCheck.Accepted)
        root.shows(RootScreen.Browse)
        assertThat(lock.current()?.videoId).isEqualTo("adultvideo1")
        assertThat(usage.surrenders(adult.id, 0)).isEmpty()
    }

    @Test
    fun awayFromTheStartPicker_theAdultsLockStillBlocksASwitch() = runBlocking<Unit> {
        lock.onVideoStarted(adult.id, "adultvideo1", null)
        // The phone's profile switch (phase 5) or any other switch: the challenge first.
        assertThat(gate.switchProfile(child.id, pin = PIN)).isEqualTo(SwitchResult.PlaybackLocked)
        assertThat(lock.current()).isNotNull()
    }

    @Test
    fun aPinSwitchOutOfTheChild_endsItsLock_andTheControllerFollows() = runBlocking<Unit> {
        assertThat(gate.switchProfile(child.id, pin = null)).isEqualTo(SwitchResult.Switched)
        lock.onVideoStarted(child.id, "kidvideo001", null)
        withTimeout(5_000) { lock.lock.first { it != null } }
        assertThat(gate.switchProfile(adult.id, PIN)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { lock.lock.first { it == null } }
        assertThat(lock.current()).isNull()
    }

    private companion object {
        const val PIN = "1357"
    }
}

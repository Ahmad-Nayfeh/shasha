package com.ahmadnayfeh.shasha.ui.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.Behavior
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
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * What the whole screen shows (SPEC §3): the setup until a parent PIN exists, the profile picker at start
 * when there are children, and who needs the PIN to go where. A child enters directly; leaving a child
 * profile, or reaching the adult one, takes the parent PIN; the adult profile turning up unasked (the
 * phone, a deleted child profile) brings the picker back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RootViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    /** Settings → «رمز الوالدين»: the tests of the optional adult PIN turn it off. */
    private val behavior = MutableStateFlow(Behavior())
    private val engine = PolicyEngine(clock) { behavior.value.policyConfig() }
    private val viewModels = ViewModelStore()

    private lateinit var profiles: ProfileRepository
    private lateinit var active: ActiveProfile
    private lateinit var pin: ParentPinRepository
    private lateinit var gate: PolicyGate
    private lateinit var deps: RootDeps

    private val adult = owner()
    private val child = kid()

    /** What the phone chose («اختيار الملف»), as TvRemoteController reports it. */
    private val choices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** AppContainer.childLetOut: the guard lets a child profile go while this is true. */
    private val letOut = MutableStateFlow(false)

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        stores = TestDataStores(tmp.root)
        val store = stores.open()
        profiles = ProfileRepository(db)
        profiles.ensureOwner(adult.name)
        active = ActiveProfile.create(profiles, store, scope, ownerName = adult.name)
        val session = SessionController(engine, active.profile, UsageRepository(db), OverridesRepository(store), scope)
        pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        gate = PolicyGate(
            engine, active, profiles, session, PlaybackLockStore(store, scope), pin, VideoLookup { LookedUpVideo(clip(it), null) }, scope,
            childLetOut = letOut,
        )
        deps = RootDeps(profiles, active, pin, gate, session, phoneChoices = choices, childLetOut = letOut, behavior = behavior)
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

    /** The device's uptime (elapsedRealtime) as the root sees it. */
    private var uptime = 1_000_000L

    private fun root(): RootViewModel =
        ViewModelProvider(viewModels, viewModelFactory { initializer { RootViewModel({ deps }, elapsed = { uptime }) } })[RootViewModel::class.java]

    /** HOME or the TV's standby for [ms], then back to the app. */
    private fun RootViewModel.awayFor(ms: Long, ownRequest: Boolean = false) {
        onAppHidden()
        uptime += ms
        onAppShown(ownRequest)
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun RootViewModel.shows(screen: RootScreen) = wait { this.screen.first { it == screen } }

    private fun RootViewModel.isIn(profile: Profile) = wait { current.first { it?.id == profile.id } }

    private fun withChild() = wait { profiles.upsert(child) }

    private fun withPin() = wait { pin.setup(PIN) }

    /** The phone chose [profileId] (after PolicyGate allowed it, or it was current). */
    private fun phoneChooses(profileId: String) {
        wait { choices.subscriptionCount.first { it > 0 } }
        assertThat(choices.tryEmit(profileId)).isTrue()
    }

    @Test
    fun firstStart_withoutAParentPin_isTheSetup_thenTheOwnersProfile() {
        val vm = root()
        vm.shows(RootScreen.Setup)

        withPin()
        vm.finishSetup()
        vm.shows(RootScreen.Browse)
        vm.isIn(adult)
    }

    @Test
    fun withoutChildProfiles_theAppStartsOnTheOwnersProfile() {
        withPin()
        root().shows(RootScreen.Browse)
    }

    @Test
    fun withAChild_theAppStartsOnThePicker_andTheAdultProfileAsksForThePin() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        vm.isIn(adult)

        // Anyone may be in front of the TV: even the profile it was left in asks, if it is the adult one.
        assertThat(vm.needsPin(adult)).isTrue()
        assertThat(vm.needsPin(child)).isFalse()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Refused(R.string.pin_required))
        assertThat(wait { vm.choose(adult, pin = "0000") }).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(vm.screen.value).isEqualTo(RootScreen.Picker(PickerMode.START))
        // BACK does not skip the choice at start.
        vm.closePicker()
        assertThat(vm.screen.value).isEqualTo(RootScreen.Picker(PickerMode.START))

        assertThat(wait { vm.choose(adult, PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
        vm.isIn(adult)
    }

    @Test
    fun adultEntryPinOff_theAdultProfileOpensWithoutThePin_butLeavingAChildStillNeedsIt() {
        withPin()
        withChild()
        behavior.value = Behavior(adultEntryPin = false)
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(vm.needsPin(adult)).isFalse()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
        vm.isIn(adult)

        // The same rule as the phone's switch (PolicyGate): from the child, the adult needs the PIN (leaving a child).
        assertThat(wait { gate.switchProfile(child.id, pin = null) }).isEqualTo(SwitchResult.Switched)
        vm.isIn(child)
        assertThat(gate.switchNeedsPin(adult)).isTrue()
        assertThat(wait { gate.switchProfile(adult.id, pin = null) }).isEqualTo(SwitchResult.PinRequired)
        assertThat(wait { vm.confirmExit(PIN) }).isEqualTo(PinCheck.Accepted)
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.isIn(adult)
    }

    @Test
    fun adultEntryPinOn_theAdultProfileAsks_onTheTvAndThroughTheGate() {
        withPin()
        withChild()
        assertThat(wait { gate.switchProfile(child.id, pin = null) }).isEqualTo(SwitchResult.Switched)
        wait { active.profile.first { it?.id == child.id } }
        assertThat(gate.switchNeedsPin(adult)).isTrue()
        behavior.value = Behavior(adultEntryPin = false)
        // Leaving the child still needs it, whatever the setting.
        assertThat(gate.switchNeedsPin(adult)).isTrue()
        behavior.value = Behavior()
        assertThat(wait { gate.switchProfile(adult.id, pin = null) }).isEqualTo(SwitchResult.PinRequired)
        assertThat(wait { gate.switchProfile(adult.id, pin = PIN) }).isEqualTo(SwitchResult.Switched)
    }

    @Test
    fun aChildEntersDirectly_andLeavesOnlyWithTheParentPin() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(wait { vm.choose(child, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
        vm.isIn(child)

        // «خروج»: a wrong PIN stays in the child's profile.
        assertThat(wait { vm.confirmExit("0000") }).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(vm.screen.value).isEqualTo(RootScreen.Browse)
        assertThat(wait { vm.confirmExit(PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Picker(PickerMode.FROM_CHILD_EXIT))

        // The PIN given at «خروج» covers the next choice: the adult profile opens without asking again.
        assertThat(vm.needsPin(adult)).isFalse()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
        vm.isIn(adult)
    }

    /** The guard's view of it: [gate]'s lockState, as the guard reads it (owner decision: a child is kept in). */
    private fun guarded(expected: Boolean): Boolean =
        wait { gate.lockState.first { engine.guardShouldEnforce(it) == expected }.let { engine.guardShouldEnforce(it) } }

    @Test
    fun aChildsExitWithThePin_letsTheGuardGo_untilTheNextChoice() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(child, pin = null) }
        vm.shows(RootScreen.Browse)
        vm.isIn(child)
        assertThat(letOut.value).isFalse()
        assertThat(guarded(true)).isTrue()

        // A wrong PIN changes nothing: still the child's, still guarded.
        wait { vm.confirmExit("0000") }
        assertThat(letOut.value).isFalse()
        // «خروج» + the PIN: the picker is the parent's, the TV is not kept on it.
        assertThat(wait { vm.confirmExit(PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Picker(PickerMode.FROM_CHILD_EXIT))
        assertThat(letOut.value).isTrue()
        assertThat(guarded(false)).isFalse()

        // BACK: back to the child, guarded again.
        vm.closePicker()
        vm.shows(RootScreen.Browse)
        assertThat(letOut.value).isFalse()
        assertThat(guarded(true)).isTrue()

        // «خروج» again, then the adult: free, as the adult.
        wait { vm.confirmExit(PIN) }
        assertThat(letOut.value).isTrue()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
        vm.isIn(adult)
        assertThat(letOut.value).isFalse()
        assertThat(guarded(false)).isFalse()
    }

    @Test
    fun theTimeUpSwitch_andTheRelock_neverLetAChildOut() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(child, pin = null) }
        vm.shows(RootScreen.Browse)

        // «تبديل الملف» on "time up": the PIN was typed to add time, the child may be the one choosing next.
        assertThat(wait { vm.switchAfterTimeUp(PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Picker(PickerMode.FROM_CHILD_EXIT))
        assertThat(letOut.value).isFalse()
        vm.closePicker()
        vm.shows(RootScreen.Browse)

        // «خروج» + PIN, then the TV left alone for longer than the relock: back to the child, guarded.
        wait { vm.confirmExit(PIN) }
        assertThat(letOut.value).isTrue()
        vm.awayFor(RootViewModel.RELOCK_AFTER_MS + 1_000)
        vm.shows(RootScreen.Browse)
        vm.isIn(child)
        assertThat(letOut.value).isFalse()
    }

    @Test
    fun theAdultsSwitch_goesToAChildWithoutThePin_andBackReturns() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(adult, PIN) }
        vm.shows(RootScreen.Browse)

        vm.openPicker()
        vm.shows(RootScreen.Picker(PickerMode.FROM_ADULT))
        // Staying in the adult profile needs no PIN from inside it; BACK returns to it.
        assertThat(vm.needsPin(adult)).isFalse()
        vm.closePicker()
        vm.shows(RootScreen.Browse)

        vm.openPicker()
        assertThat(vm.needsPin(child)).isFalse()
        assertThat(wait { vm.choose(child, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.isIn(child)
        vm.shows(RootScreen.Browse)
    }

    @Test
    fun theAdultProfileTurningUpUnasked_bringsThePickerBack() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(wait { vm.choose(child, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.isIn(child)
        vm.shows(RootScreen.Browse)

        // The phone switched the TV to the adult profile (with the PIN, on the phone): the TV asks again.
        assertThat(wait { gate.switchProfile(adult.id, PIN) }).isEqualTo(SwitchResult.Switched)
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(vm.needsPin(adult)).isTrue()
    }

    @Test
    fun onThePicker_aChildProfileThePhoneChose_opens_butTheAdultIsStillAskedForHere() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        vm.isIn(adult)

        // The adult profile chosen on the phone (with its PIN there): whoever holds the TV's remote is asked here.
        phoneChooses(adult.id)
        wait { kotlinx.coroutines.delay(200) }
        assertThat(vm.screen.value).isEqualTo(RootScreen.Picker(PickerMode.START))

        // The phone switched to the child (entering a child profile needs no PIN) and told the parent it did.
        assertThat(wait { gate.switchProfile(child.id, pin = null) }).isEqualTo(SwitchResult.Switched)
        phoneChooses(child.id)
        vm.shows(RootScreen.Browse)
        vm.isIn(child)
        // Leaving it still takes the parent PIN.
        assertThat(vm.needsPin(adult)).isTrue()
    }

    @Test
    fun onThePicker_theChildTheTvWasLeftIn_chosenOnThePhone_opens() {
        withPin()
        withChild()
        assertThat(wait { gate.switchProfile(child.id, pin = null) }).isEqualTo(SwitchResult.Switched)
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        vm.isIn(child)
        phoneChooses(child.id)
        vm.shows(RootScreen.Browse)
        vm.isIn(child)
    }

    private fun enterAdult(vm: RootViewModel) {
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(wait { vm.choose(adult, PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Browse)
    }

    @Test
    fun backFromHomeOrStandby_theAdultProfileAsksForThePinAgain() {
        withPin()
        withChild()
        val vm = root()
        enterAdult(vm)

        // The owner pressed HOME (or the TV went to standby) and the process stayed up: a child opens the app.
        vm.awayFor(8 * 3_600_000L)
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(vm.needsPin(adult)).isTrue()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Refused(R.string.pin_required))
        vm.closePicker() // BACK does not skip the choice
        assertThat(vm.screen.value).isEqualTo(RootScreen.Picker(PickerMode.START))
        assertThat(wait { vm.choose(child, pin = null) }).isEqualTo(PinCheck.Accepted)
        vm.isIn(child)
    }

    @Test
    fun theAdultsOwnPicker_isLockedToo_whenTheAppComesBack() {
        withPin()
        withChild()
        val vm = root()
        enterAdult(vm)
        vm.openPicker()
        assertThat(vm.needsPin(adult)).isFalse()
        vm.awayFor(RootViewModel.RELOCK_AFTER_MS)
        vm.shows(RootScreen.Picker(PickerMode.START))
        assertThat(vm.needsPin(adult)).isTrue()
    }

    @Test
    fun aMomentAway_orTheAppsOwnFilePicker_changesNothing() {
        withPin()
        withChild()
        val vm = root()
        enterAdult(vm)

        vm.awayFor(RootViewModel.RELOCK_AFTER_MS - 1) // the guard brings the app straight back
        // The import's document picker, however long it took: its answer is on the way.
        vm.awayFor(10 * 60_000L, ownRequest = true)
        wait { kotlinx.coroutines.delay(100) }
        assertThat(vm.screen.value).isEqualTo(RootScreen.Browse)
        vm.isIn(adult)
    }

    @Test
    fun withoutChildProfiles_orInAChildsProfile_comingBackAsksNothing() {
        withPin()
        val alone = root()
        alone.shows(RootScreen.Browse)
        alone.awayFor(3_600_000L)
        wait { kotlinx.coroutines.delay(100) }
        assertThat(alone.screen.value).isEqualTo(RootScreen.Browse)
        viewModels.clear()

        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(child, pin = null) }
        vm.shows(RootScreen.Browse)
        vm.awayFor(3_600_000L)
        wait { kotlinx.coroutines.delay(100) }
        assertThat(vm.screen.value).isEqualTo(RootScreen.Browse)
        vm.isIn(child)
    }

    @Test
    fun aChildsExitPin_isForgottenWhileTheAppIsAway() {
        withPin()
        withChild()
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(child, pin = null) }
        vm.shows(RootScreen.Browse)
        assertThat(wait { vm.confirmExit(PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Picker(PickerMode.FROM_CHILD_EXIT))

        // The parent walked away and the TV slept: back in the child's profile, and the PIN is forgotten.
        vm.awayFor(RootViewModel.RELOCK_AFTER_MS)
        vm.shows(RootScreen.Browse)
        vm.isIn(child)
        assertThat(vm.needsPin(adult)).isTrue()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Refused(R.string.pin_required))
        vm.isIn(child)
    }

    @Test
    fun thePinTypedToAddTime_doesNotOpenTheAdultProfile() {
        withPin()
        withChild()
        val brother = kid(id = "kid2", name = "عمر")
        wait { profiles.upsert(brother) }
        val vm = root()
        vm.shows(RootScreen.Picker(PickerMode.START))
        wait { vm.choose(child, pin = null) }
        vm.shows(RootScreen.Browse)

        // «انتهى الوقت» → the parent's PIN → «تبديل الملف»: the picker, where only another child opens freely.
        assertThat(wait { vm.switchAfterTimeUp("0000") }).isInstanceOf(PinCheck.Wrong::class.java)
        assertThat(vm.screen.value).isEqualTo(RootScreen.Browse)
        assertThat(wait { vm.switchAfterTimeUp(PIN) }).isEqualTo(PinCheck.Accepted)
        vm.shows(RootScreen.Picker(PickerMode.FROM_CHILD_EXIT))
        assertThat(vm.needsPin(adult)).isTrue()
        assertThat(vm.needsPin(brother)).isFalse()
        assertThat(wait { vm.choose(adult, pin = null) }).isEqualTo(PinCheck.Refused(R.string.pin_required))
        vm.isIn(child)
        assertThat(wait { vm.choose(adult, PIN) }).isEqualTo(PinCheck.Accepted)
        vm.isIn(adult)
    }

    private companion object {
        const val PIN = "1357"
    }
}

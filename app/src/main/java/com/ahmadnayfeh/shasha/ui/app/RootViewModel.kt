package com.ahmadnayfeh.shasha.ui.app

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.AppContainer
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.toCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the whole screen shows. */
sealed interface RootScreen {
    /** Reading the current profile and whether a parent PIN exists (the splash stays up meanwhile). */
    data object Loading : RootScreen

    /** First start: no parent PIN yet. */
    data object Setup : RootScreen

    data class Picker(val mode: PickerMode) : RootScreen

    /** The current profile's screens. */
    data object Browse : RootScreen
}

enum class PickerMode {
    /** The app just started: anyone may be sitting in front of the TV, so the adult profile asks for the PIN. */
    START,

    /** The adult chose «تبديل الملف»: BACK returns to the adult profile. */
    FROM_ADULT,

    /**
     * Leaving a child profile after the parent PIN (its «خروج», or «تبديل الملف» on its "time up" screen):
     * the next choice uses that PIN, once. BACK returns to the child.
     */
    FROM_CHILD_EXIT,
}

/** What the root needs from the app (built off the main thread: creating the current profile reads the disk). */
class RootDeps(
    val profiles: ProfileRepository,
    val active: ActiveProfile,
    val pin: ParentPinRepository,
    val gate: PolicyGate,
    val session: SessionController,
    /** True while a profile's screens are on screen (not the setup or the picker): its time counts then. */
    val profileScreensShown: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** SPEC §4's playback lock (null: nothing is ever locked, e.g. in tests of the profiles alone). */
    val lock: LockController? = null,
)

/**
 * Decides what the whole screen shows (SPEC §3): the first-start setup until a parent PIN exists, the
 * profile picker when the app starts (if there are child profiles) and whenever someone switches, then the
 * current profile's screens. Every switch goes through [PolicyGate.switchProfile] (the engine's PIN rules);
 * this only adds when the TV itself must ask again:
 *
 * - At start, the adult profile always asks for the PIN, even when it is the one the TV was left in. A
 *   child's locked video (SPEC §4) skips the picker instead: the TV goes straight back to it.
 * - The same when the app comes back after [RELOCK_AFTER_MS] or more out of sight (HOME, the TV's
 *   standby): the process, and the adult profile with it, may have stayed up all night ([onAppHidden],
 *   [onAppShown]).
 * - When the current profile turns from a child into the adult without this screen asking (the child's
 *   profile was deleted, the phone switched), the picker comes back first.
 *
 * [elapsed] is a clock the viewer cannot set (elapsedRealtime).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModel(
    private val load: suspend () -> RootDeps,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val _screen = MutableStateFlow<RootScreen>(RootScreen.Loading)
    val screen: StateFlow<RootScreen> = _screen.asStateFlow()

    private val _deps = MutableStateFlow<RootDeps?>(null)
    val deps: StateFlow<RootDeps?> = _deps.asStateFlow()

    /** Every profile, in the picker's order. */
    val profiles: StateFlow<List<Profile>> = _deps.flatMapLatest { it?.profiles?.observeAll() ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The profile the screens are for; null only while loading. */
    val current: StateFlow<Profile?> = _deps.flatMapLatest { it?.active?.profile ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The PIN a child profile was left with, for the picker's next choice only. */
    private var exitPin: String? = null

    /**
     * Whether [exitPin] also opens the adult profile: yes after «خروج» (the parent asked to leave), no after
     * the "time up" screen, where the PIN was typed to add time (the child may be the one choosing next).
     */
    private var exitPinOpensAdult = false

    /** The profile this screen put on (or found at start); another one appearing unasked is checked. */
    private var trusted: String? = null

    /** When the app went out of sight ([elapsed]); null while it is on screen. */
    private var hiddenAt: Long? = null

    init {
        viewModelScope.launch {
            val deps = load()
            _deps.value = deps
            val pinSet = deps.pin.isSet.first()
            val profile = deps.active.awaitProfile()
            val first = when {
                !pinSet -> RootScreen.Setup
                // A child's locked video goes on after a restart or a reboot (SPEC §4): straight back to it.
                profile.isChild && deps.lock?.current()?.profileId == profile.id -> RootScreen.Browse
                deps.profiles.all().any { it.isChild } -> RootScreen.Picker(PickerMode.START)
                else -> RootScreen.Browse
            }
            // Only the first screen: nothing on screen can have chosen anything yet, but never undo a choice.
            if (_screen.value == RootScreen.Loading) {
                trusted = profile.id
                _screen.value = first
            }
            deps.active.profile.filterNotNull().collect(::onProfile)
        }
    }

    private fun onProfile(profile: Profile) {
        // On the picker the next choice decides anyway; the setup only renames the owner.
        if (profile.id == trusted || _screen.value != RootScreen.Browse) return
        if (profile.isAdult) {
            forgetExitPin()
            _screen.value = RootScreen.Picker(PickerMode.START)
        } else {
            trusted = profile.id
        }
    }

    /** The setup is done: with a child profile to choose, the picker; otherwise the owner's profile. */
    fun finishSetup() {
        viewModelScope.launch {
            val deps = _deps.value ?: return@launch
            trusted = deps.active.awaitProfile().id
            _screen.value = if (deps.profiles.all().any { it.isChild }) RootScreen.Picker(PickerMode.START) else RootScreen.Browse
        }
    }

    /** The adult's «تبديل الملف». */
    fun openPicker() {
        forgetExitPin()
        _screen.value = RootScreen.Picker(PickerMode.FROM_ADULT)
    }

    /** A child's «خروج»: the parent PIN, then the picker, where it opens the next choice (the adult profile too). */
    suspend fun confirmExit(pin: String): PinCheck = leaveChild(pin, opensAdult = true)

    /**
     * «تبديل الملف» on the "time up" screen, with the PIN given there to add time: the picker, where another
     * child profile opens without asking again but the adult profile asks for the PIN (whoever holds the
     * remote now may not be the parent who typed it).
     */
    suspend fun switchAfterTimeUp(pin: String): PinCheck = leaveChild(pin, opensAdult = false)

    private suspend fun leaveChild(pin: String, opensAdult: Boolean): PinCheck {
        val deps = _deps.value ?: return PinCheck.Refused(R.string.pin_check_failed)
        val result = deps.gate.verifyParentPin(pin).toCheck()
        if (result == PinCheck.Accepted) {
            exitPin = pin
            exitPinOpensAdult = opensAdult
            _screen.value = RootScreen.Picker(PickerMode.FROM_CHILD_EXIT)
        }
        return result
    }

    /** BACK on the picker (not at start): back to the profile it was opened from. */
    fun closePicker() {
        val screen = _screen.value as? RootScreen.Picker ?: return
        if (screen.mode == PickerMode.START) return
        forgetExitPin()
        _screen.value = RootScreen.Browse
    }

    /** The app went out of sight: HOME, another app, the TV's standby. */
    fun onAppHidden() {
        if (hiddenAt == null) hiddenAt = elapsed()
    }

    /**
     * The app is on screen again. After [RELOCK_AFTER_MS] or more away anyone may be holding the remote,
     * as at start: the adult profile asks for the PIN again (when there are child profiles to choose
     * instead), and a PIN given to leave a child profile is forgotten (its picker returns to the child).
     * [ownRequest]: the app itself opened what was in front (the document picker of the import), and its
     * answer is on the way: nothing changes.
     */
    fun onAppShown(ownRequest: Boolean = false) {
        val since = hiddenAt ?: return
        hiddenAt = null
        if (ownRequest || elapsed() - since < RELOCK_AFTER_MS) return
        forgetExitPin()
        val screen = _screen.value
        if (screen is RootScreen.Picker && screen.mode == PickerMode.FROM_CHILD_EXIT) {
            _screen.value = RootScreen.Browse
            return
        }
        viewModelScope.launch {
            val deps = _deps.value ?: return@launch
            val onAdult = when (val shown = _screen.value) {
                RootScreen.Browse -> true
                is RootScreen.Picker -> shown.mode == PickerMode.FROM_ADULT
                else -> false
            }
            if (!onAdult || !deps.active.awaitProfile().isAdult || deps.profiles.all().none { it.isChild }) return@launch
            // Only if nothing was chosen meanwhile.
            if (_screen.value == screen) _screen.value = RootScreen.Picker(PickerMode.START)
        }
    }

    private fun forgetExitPin() {
        exitPin = null
        exitPinOpensAdult = false
    }

    /** The PIN given to leave a child profile, when it also opens [target]. */
    private fun exitPinFor(target: Profile): String? = exitPin?.takeIf { target.isChild || exitPinOpensAdult }

    /** Whether choosing [target] on the picker asks for the parent PIN first. */
    fun needsPin(target: Profile): Boolean {
        val current = current.value ?: return true
        val mode = (_screen.value as? RootScreen.Picker)?.mode ?: PickerMode.START
        if (exitPinFor(target) != null) return false
        return when {
            target.isAdult -> !(mode == PickerMode.FROM_ADULT && target.id == current.id)
            target.id == current.id -> false
            else -> current.isChild // leaving a child profile
        }
    }

    /**
     * Enters [target] ([pin] when [needsPin] asked for it). Staying in the current profile checks the PIN
     * itself where it is needed (the adult at start); everything else is a [PolicyGate.switchProfile].
     * On the start picker another profile also ends a locked adult video left behind (the TV was switched
     * off mid-video), as a surrender: nobody is kept from the TV by it (SPEC §4).
     */
    suspend fun choose(target: Profile, pin: String?): PinCheck {
        val deps = _deps.value ?: return PinCheck.Refused(R.string.pin_check_failed)
        val pinNeeded = needsPin(target)
        if (pinNeeded && pin == null) return PinCheck.Refused(R.string.pin_required)
        val usePin = pin ?: exitPinFor(target)
        val current = deps.active.awaitProfile()
        val atStart = (_screen.value as? RootScreen.Picker)?.mode == PickerMode.START
        // Trusted before the switch: the new profile arrives (onProfile) before the switch returns.
        trusted = target.id
        val result = when {
            target.id != current.id -> deps.gate.switchProfile(target.id, usePin, fromStartPicker = atStart).toCheck()
            pinNeeded -> deps.gate.verifyParentPin(checkNotNull(usePin)).toCheck()
            else -> PinCheck.Accepted
        }
        if (result == PinCheck.Accepted) {
            forgetExitPin()
            _screen.value = RootScreen.Browse
        } else {
            trusted = current.id
        }
        return result
    }

    /** How long parent PIN checks are still locked out (0 when they are not): the pads open showing it. */
    suspend fun pinLockMillis(): Long = _deps.value?.pin?.remainingLockMillis() ?: 0L

    companion object {
        /**
         * Out of sight for longer than this, the TV may be in anyone's hands. Short enough for "HOME, hand the
         * remote over", long enough for the guard (phase 4) bringing the app back within a second or two.
         */
        const val RELOCK_AFTER_MS = 15_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RootViewModel({
                    withContext(Dispatchers.IO) {
                        RootDeps(
                            container.profiles,
                            container.currentProfile,
                            container.parentPin,
                            container.policy,
                            container.session,
                            container.profileScreensShown,
                            container.lock,
                        )
                    }
                })
            }
        }
    }
}

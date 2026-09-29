package com.ahmadnayfeh.shasha.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.AppMessageHost
import com.ahmadnayfeh.shasha.ui.components.AppMessages
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.LocalTopClearance
import com.ahmadnayfeh.shasha.ui.components.TopClearance
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinDialog
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.toCheck
import com.ahmadnayfeh.shasha.ui.navigation.ProfileNavHost
import com.ahmadnayfeh.shasha.ui.screens.profiles.ProfilePickerRoute
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupRoute
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent

/**
 * The whole app on screen: the first-start setup, the profile picker, or the current profile's screens
 * with the session's gate ("time up") and its quiet 5-minute notice on top.
 */
@Composable
fun ShashaRoot(vm: RootViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val deps by vm.deps.collectAsStateWithLifecycle()
    val messages = remember { AppMessages() }
    CompositionLocalProvider(LocalAppMessages provides messages) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(tween(300, delayMillis = 80)) togetherWith fadeOut(tween(180)) },
                modifier = Modifier.fillMaxSize(),
                label = "root",
            ) { shown ->
                val ready = deps
                when {
                    shown == RootScreen.Loading || ready == null -> Box(Modifier.fillMaxSize()) // the splash is still up
                    shown == RootScreen.Setup -> SetupRoute(ready.profiles, ready.pin, onDone = vm::finishSetup)
                    shown is RootScreen.Picker -> ProfilePickerRoute(vm, shown.mode)
                    else -> BrowseRoot(vm, ready)
                }
            }
            AppMessageHost(messages)
        }
    }
}

@Composable
private fun BrowseRoot(vm: RootViewModel, deps: RootDeps) {
    // The profile's time counts only while its screens are shown (not on the setup or the picker).
    DisposableEffect(deps.profileScreensShown) {
        deps.profileScreensShown.value = true
        onDispose { deps.profileScreensShown.value = false }
    }
    val profile = vm.current.collectAsStateWithLifecycle().value ?: return
    // A new profile gets new screens (and a new back stack): nothing carries over from the last one.
    key(profile.id, profile.kind) {
        ProfileScope {
            if (profile.isChild) KidsAccent { ProfileScreens(profile, vm, deps) } else ProfileScreens(profile, vm, deps)
        }
    }
}

@Composable
private fun ProfileScreens(profile: Profile, vm: RootViewModel, deps: RootDeps) {
    val state by deps.session.state.collectAsStateWithLifecycle()
    val ready by deps.session.isReady.collectAsStateWithLifecycle()
    var exiting by rememberSaveable { mutableStateOf(false) }
    val unlock = remember(deps) { SessionUnlock(deps, vm::switchAfterTimeUp) }
    // The player's title reserves the top of the screen while its controls show: the notice goes below it.
    val clearance = remember { TopClearance() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTopClearance provides clearance) {
            SessionGate(state, ready, profile, unlock) {
                val nav = rememberNavController()
                ProfileNavHost(
                    profile = profile,
                    nav = nav,
                    onSwitchProfile = vm::openPicker,
                    onExitProfile = { exiting = true },
                )
            }
            WarningBanner(deps.session.warningEvents, state)
        }
    }
    if (exiting) {
        PinDialog(onDismiss = { exiting = false }) {
            ParentPinPad(
                title = stringResource(R.string.pin_parent_title),
                subtitle = stringResource(R.string.pin_exit_body, profile.name),
                check = vm::confirmExit,
                onAccepted = { exiting = false },
                initialLockMillis = vm::pinLockMillis,
                header = { ProfileAvatar(profile.avatar, profile.name, 64.dp) },
            )
        }
    }
}

/**
 * The "time up" screen's unlock, on the app's real session and PIN; [leave] is the picker's way out (where
 * that PIN does not open the adult profile: [RootViewModel.switchAfterTimeUp]).
 */
private class SessionUnlock(private val deps: RootDeps, private val leave: suspend (String) -> PinCheck) : TimeUnlock {
    override suspend fun check(pin: String): PinCheck = deps.gate.verifyParentPin(pin).toCheck()

    override suspend fun lockMillis(): Long = deps.pin.remainingLockMillis()

    override suspend fun grant(choice: UnlockChoice) {
        val minutes = choice.minutes
        if (minutes == null) deps.session.unlimitedToday() else deps.session.grantUnlock(minutes)
    }

    override suspend fun switchProfile(pin: String): PinCheck = leave(pin)
}

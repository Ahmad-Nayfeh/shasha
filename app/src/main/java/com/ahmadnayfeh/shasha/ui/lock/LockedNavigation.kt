package com.ahmadnayfeh.shasha.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * While [profileId] has a locked video (SPEC §4), its screens show a player and nothing else: the locked
 * video comes back on screen after a restart or a reboot, after the adult typed his PIN again (coming back
 * to the TV rebuilds the profile's screens), and whenever anything tried to navigate elsewhere meanwhile (a
 * deep link, a stray BACK, a screen opened from the player). A player of another video on top of it (one
 * the lock refused) may stay: leaving it returns to the locked one. The Shorts player is a player too: a Short that is locked
 * there stays there; one the lock brings back after a restart plays in the player.
 *
 * Inside a playlist the video's position is not known here: the player looks it up itself.
 */
@Composable
fun KeepLockedVideoOnScreen(locks: StateFlow<PlaybackLock?>, profileId: String, nav: NavHostController) {
    LaunchedEffect(nav, locks, profileId) {
        val locked = locks.map { lock -> lock?.takeIf { it.profileId == profileId } }.distinctUntilChanged()
        combine(locked, nav.currentBackStackEntryFlow) { lock, entry -> lock to entry }.collect { (lock, entry) ->
            if (lock == null || entry.destination.hasRoute<Screen.Player>() || entry.destination.hasRoute<Screen.Shorts>()) return@collect
            nav.navigate(Screen.Player(lock.videoId, lock.playlistId)) { launchSingleTop = true }
        }
    }
}

package com.ahmadnayfeh.shasha.ui.lock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §4: "killing the app, pulling the plug or a reboot must not drop the lock". The lock is stored; a new
 * process reads it back, the profile's screens (which start on the home) go straight back to the locked
 * video, and it is still locked there. The same when the screens are rebuilt in the same process (the
 * activity recreated, or the adult's PIN asked again after the TV was left).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class LockRestoreTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var stores: TestDataStores
    private val scope = testScope()
    private val clock = MutableClock(instantAt("2026-09-28T12:00"))
    private val engine = PolicyEngine(clock)
    private val child = kid()
    private val current = MutableStateFlow<Profile?>(child)

    @Before
    fun setUp() {
        stores = TestDataStores(tmp.root)
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
    }

    private fun controller(store: DataStore<Preferences>): LockController {
        val pin = ParentPinRepository(store, clock, PinHasher(iterations = 1_000))
        return LockController(engine, PlaybackLockStore(store, scope), current, pin::verify, pin::remainingLockMillis, {}, scope)
    }

    /** A profile's screens as the app builds them: the home first, the player, the lock keeping its video on screen. */
    @androidx.compose.runtime.Composable
    private fun ProfileScreens(lock: LockController, nav: NavHostController) {
        NavHost(nav, startDestination = Screen.Main) {
            composable<Screen.Main> { Box(Modifier.fillMaxSize().focusable().testTag("home")) }
            composable<Screen.Player> { entry ->
                val route = entry.toRoute<Screen.Player>()
                val current by lock.lock.collectAsState()
                val method = lockMethodFor(current, child.id, route.videoId, route.playlistId)
                LockedPlayback(method, lock.playerActions(), onLeave = { nav.popBackStack() }) {
                    Box(Modifier.fillMaxSize().testTag("player_${route.videoId}_${route.playlistId}"))
                }
            }
        }
        KeepLockedVideoOnScreen(lock.lock, child.id, nav)
    }

    private fun exists(tag: String) =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty()

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aNewProcess_readsTheLockBack_andItsVideoComesBackOnScreen_stillLocked() {
        // The child's playlist video plays: locked. Then the process dies (the TV is unplugged).
        runBlocking { controller(stores.open()).onVideoStarted(child.id, VIDEO, ALLOWED_PLAYLIST) }
        val restarted = controller(stores.restart())

        compose.setContent { ShashaTheme { ProfileScreens(restarted, rememberNavController()) } }
        compose.waitUntil(5_000) { exists("player_${VIDEO}_$ALLOWED_PLAYLIST") }
        assertThat(exists("home")).isFalse()

        // Still locked: BACK asks for the parent PIN, the home does not come back.
        systemBack()
        compose.onNodeWithTag("lock_pin_prompt").assertExists()
        assertThat(exists("home")).isFalse()
    }

    @Test
    fun rebuiltScreens_andARecreatedActivity_comeBackToTheLockedVideo() {
        val lock = controller(stores.open())
        runBlocking { lock.onVideoStarted(child.id, VIDEO, null) }
        var screens by mutableStateOf(0)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ShashaTheme {
                // A new NavController each time the screens are rebuilt (the profile's screens left and came back).
                androidx.compose.runtime.key(screens) { ProfileScreens(lock, rememberNavController()) }
            }
        }
        compose.waitUntil(5_000) { exists("player_${VIDEO}_null") }

        // The screens are rebuilt from scratch (the adult's PIN asked again after the TV was left, say).
        screens++
        compose.waitUntil(5_000) { exists("player_${VIDEO}_null") }

        // The activity is recreated: its saved state comes back, the locked video with it.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { exists("player_${VIDEO}_null") }
        assertThat(exists("home")).isFalse()

        // Unlocked (the phone's parent section): the home is reachable again.
        runBlocking { lock.unlockByParent() }
        compose.waitUntil(5_000) { lock.lock.value == null }
        compose.waitForIdle()
        systemBack()
        compose.waitUntil(5_000) { exists("home") }
    }

    private companion object {
        const val VIDEO = "lockedvid01"
    }
}

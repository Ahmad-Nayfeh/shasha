package com.ahmadnayfeh.shasha.ui.app

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.RIYADH
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.epochMsAt
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPEC §3: when the day's time is up (or outside the allowed hours) the profile's screens make way for a
 * full-screen notice, BACK cannot dismiss it, and only the parent PIN opens it (+15 / +30 / +60 / rest of
 * the day). What was on screen comes back as it was.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SessionGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val child = Profile.newChild("سارة", id = "kid")
    private var session by mutableStateOf<SessionState>(SessionState.Ok(3_600))
    private var ready by mutableStateOf(true)
    private var backsBehind = 0
    private val unlock = FakeUnlock()

    private class FakeUnlock : TimeUnlock {
        val grants = mutableListOf<UnlockChoice>()
        val pins = mutableListOf<String>()

        override suspend fun check(pin: String): PinCheck {
            pins += pin
            return if (pin == "2468") PinCheck.Accepted else PinCheck.Wrong(attemptsLeft = 4)
        }

        override suspend fun lockMillis(): Long = 0

        override suspend fun grant(choice: UnlockChoice) {
            grants += choice
        }

        val switches = mutableListOf<String>()

        override suspend fun switchProfile(pin: String): PinCheck {
            switches += pin
            return PinCheck.Accepted
        }
    }

    private fun show() {
        compose.setContent {
            ShashaTheme {
                SessionGate(session, ready, child, unlock, nowMs = { epochMsAt("2026-09-28T22:30") }, zone = RIYADH) {
                    // Stands in for the profile's screens: a BACK handler (like the player's) and some saved state.
                    BackHandler { backsBehind++ }
                    var clicks by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Button(onClick = { clicks++ }, modifier = Modifier.testTag("behind")) { Text("clicks") }
                        Text(clicks.toString(), modifier = Modifier.testTag("behind_clicks"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** BACK the way the remote sends it: a key event through the activity. */
    private fun remoteBack() {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun timeUpReplacesTheProfilesScreens_andBackCannotDismissIt() {
        show()
        compose.onNodeWithTag("behind").assertExists()

        session = SessionState.TimeUp
        compose.waitForIdle()
        compose.onNodeWithTag("time_up_screen").assertExists()
        compose.onNodeWithTag("behind").assertDoesNotExist()
        compose.onNodeWithTag("block_unlock").assertIsFocused()

        repeat(3) {
            systemBack()
            remoteBack()
        }
        compose.onNodeWithTag("time_up_screen").assertExists()
        assertThat(backsBehind).isEqualTo(0)
        assertThat(compose.activity.isFinishing).isFalse()
    }

    @Test
    fun theParentUnlocksWithThePin_andTheScreensComeBackAsTheyWere() {
        show()
        // TV surfaces take OK from the remote (their semantic click), not touches.
        repeat(2) { compose.onNodeWithTag("behind").performSemanticsAction(SemanticsActions.OnClick) }
        compose.onNodeWithTag("behind_clicks").assertTextEquals("2")

        session = SessionState.TimeUp
        compose.waitForIdle()
        press(Key.DirectionCenter) // «فتح بالرمز»
        compose.onNodeWithTag("pin_pad").assertExists()

        // BACK from the PIN goes back to the notice, never behind it.
        systemBack()
        compose.onNodeWithTag("block_unlock").assertExists()
        press(Key.DirectionCenter)

        listOf(Key.Two, Key.Four, Key.Six, Key.Eight).forEach(::press)
        press(Key.Enter)
        assertThat(unlock.pins).containsExactly("2468")
        compose.onNodeWithTag("unlock_choices").assertExists()
        compose.onNodeWithTag("unlock_PLUS_15").assertIsFocused()
        press(Key.DirectionLeft) // +30 lies to the left in Arabic
        press(Key.DirectionCenter)
        assertThat(unlock.grants).containsExactly(UnlockChoice.PLUS_30)

        // The session reopens once the grant is stored.
        session = SessionState.Ok(30 * 60L)
        compose.waitForIdle()
        compose.onNodeWithTag("time_up_screen").assertDoesNotExist()
        compose.onNodeWithTag("behind_clicks").assertTextEquals("2")
    }

    @Test
    fun afterThePin_theParentCanLeaveForAnotherProfile_insteadOfGivingTime() {
        show()
        session = SessionState.TimeUp
        compose.waitForIdle()
        press(Key.DirectionCenter)
        listOf(Key.Two, Key.Four, Key.Six, Key.Eight).forEach(::press)
        press(Key.Enter)
        compose.onNodeWithTag("unlock_choices").assertExists()
        compose.onNodeWithTag("unlock_switch_profile").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertThat(unlock.switches).containsExactly("2468")
        assertThat(unlock.grants).isEmpty()
    }

    @Test
    fun theChoicesCloseByThemselves_andForgetThePin() {
        show()
        session = SessionState.TimeUp
        compose.waitForIdle()
        press(Key.DirectionCenter)
        listOf(Key.Two, Key.Four, Key.Six, Key.Eight).forEach(::press)
        press(Key.Enter)
        compose.onNodeWithTag("unlock_choices").assertExists()

        // The parent puts the remote down: a key now and then keeps the choices open…
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(CHOICES_IDLE_MS - 5_000)
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(CHOICES_IDLE_MS - 5_000)
        compose.onNodeWithTag("unlock_choices").assertExists()
        // …a minute without one closes them, and the PIN goes with them.
        compose.mainClock.advanceTimeBy(10_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("unlock_choices").assertDoesNotExist()
        compose.onNodeWithTag("block_unlock").assertExists()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("pin_pad").assertExists()
        assertThat(unlock.grants).isEmpty()
        assertThat(unlock.switches).isEmpty()
    }

    @Test
    fun theProfileChip_givesWayToThePinPad() {
        show()
        session = SessionState.TimeUp
        compose.waitForIdle()
        compose.onNodeWithTag("profile_chip").assertExists()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("pin_pad").assertExists()
        compose.onNodeWithTag("profile_chip").assertDoesNotExist()
        // The compact card fits the screen's margins: its keypad keeps its full size.
        compose.onNodeWithTag("pin_submit").assertHeightIsEqualTo(54.dp)
        val panel = compose.onNodeWithTag("pin_panel").getUnclippedBoundsInRoot()
        assertThat(panel.top.value).isAtLeast(48f)
        assertThat(panel.bottom.value).isAtMost(540f - 48f)
    }

    @Test
    fun aWrongPinOpensNothing() {
        show()
        session = SessionState.TimeUp
        compose.waitForIdle()
        press(Key.DirectionCenter)
        listOf(Key.One, Key.One, Key.One, Key.One).forEach(::press)
        press(Key.Enter)
        compose.onNodeWithTag("pin_notice").assertExists()
        compose.onNodeWithTag("unlock_choices").assertDoesNotExist()
        assertThat(unlock.grants).isEmpty()
    }

    @Test
    fun outsideTheAllowedHours_sayWhenWatchingReopens() {
        show()
        session = SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00"))
        compose.waitForIdle()
        compose.onNodeWithTag("outside_schedule_screen").assertExists()
        compose.onNodeWithTag("block_reopens").assertTextEquals("تعود المشاهدة غداً الساعة 7:00 ص")
        systemBack()
        compose.onNodeWithTag("outside_schedule_screen").assertExists()
    }

    @Test
    fun beforeTheSessionIsKnown_nothingIsBlocked() {
        ready = false
        session = SessionState.TimeUp
        show()
        compose.onNodeWithTag("behind").assertExists()
        ready = true
        compose.waitForIdle()
        compose.onNodeWithTag("time_up_screen").assertExists()
    }
}

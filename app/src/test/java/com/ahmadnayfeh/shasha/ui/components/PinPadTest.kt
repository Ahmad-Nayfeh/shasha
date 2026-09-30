package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's own PIN pad (no system keyboard near a child): digits from the D-pad grid or the remote's
 * number keys, backspace and clear, «تأكيد» only from 4 digits, and the attempt limiter's answers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PinPadTest {
    @get:Rule
    val compose = createComposeRule()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Keys pressed one after the other ([Key] is a value class: no varargs of it). */
    private fun press(keys: List<Key>) = keys.forEach { press(it) }

    private fun dots(count: Int) = compose.onNodeWithTag("pin_dots").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, count.toString()))

    @Test
    fun theDpadGridAndTheNumberKeysType_backspaceAndClearErase_confirmSubmits() {
        val state = PinPadState()
        val submitted = mutableListOf<String>()
        compose.setContent { ShashaTheme { PinPad(state, "رمز الوالدين", onSubmit = { submitted += it }) } }
        compose.waitForIdle()

        // Focus starts in the middle of the grid, on 5.
        compose.onNodeWithTag("pin_key_5").assertIsFocused()
        press(Key.DirectionCenter)
        press(listOf(Key.DirectionUp, Key.DirectionLeft)) // 1, top left: the keypad is laid out like every keypad
        compose.onNodeWithTag("pin_key_1").assertIsFocused()
        press(Key.DirectionCenter)
        press(listOf(Key.Nine, Key.Zero)) // the remote's number keys
        assertThat(state.digits).isEqualTo("5190")
        dots(4)

        // A number key took the focus to «تأكيد».
        compose.onNodeWithTag("pin_submit").assertIsFocused()

        press(Key.Backspace)
        assertThat(state.digits).isEqualTo("519")
        // Three digits are not a PIN: «تأكيد» does nothing yet.
        press(Key.DirectionCenter)
        assertThat(submitted).isEmpty()

        // Up to the last row, then «مسح», which empties it.
        press(Key.DirectionUp)
        compose.onNodeWithTag("pin_key_0").assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithTag("pin_key_clear").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(state.digits).isEmpty()
        dots(0)

        press(listOf(Key.Two, Key.Four, Key.Six, Key.Eight))
        press(Key.DirectionDown) // nothing below «تأكيد»
        compose.onNodeWithTag("pin_submit").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(submitted).containsExactly("2468")
    }

    @Test
    fun numberKeysThenOk_confirms_insteadOfTypingTheFocusedKey() {
        val state = PinPadState()
        val submitted = mutableListOf<String>()
        compose.setContent { ShashaTheme { PinPad(state, "رمز الوالدين", onSubmit = { submitted += it }) } }
        compose.waitForIdle()
        compose.onNodeWithTag("pin_key_5").assertIsFocused()

        press(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.DirectionCenter))
        assertThat(state.digits).isEqualTo("1234")
        assertThat(submitted).containsExactly("1234")
    }

    @Test
    fun enter_pressesTheFocusedKey_likeOk() {
        // Some remotes and keyboards send ENTER for OK: the D-pad grid must still type.
        val state = PinPadState()
        val submitted = mutableListOf<String>()
        compose.setContent { ShashaTheme { PinPad(state, "رمز الوالدين", onSubmit = { submitted += it }) } }
        compose.waitForIdle()

        press(Key.Enter) // 5
        press(listOf(Key.DirectionUp, Key.Enter)) // 2
        press(listOf(Key.DirectionRight, Key.Enter)) // 3
        press(listOf(Key.DirectionDown, Key.DirectionDown, Key.Enter)) // 9
        assertThat(state.digits).isEqualTo("5239")
        assertThat(submitted).isEmpty()
        press(listOf(Key.DirectionDown, Key.DirectionDown))
        compose.onNodeWithTag("pin_submit").assertIsFocused()
        press(Key.NumPadEnter)
        assertThat(submitted).containsExactly("5239")
    }

    @Test
    fun aWrongPin_bringsTheFocusBackToTheMiddleOfTheGrid() {
        compose.setContent { ShashaTheme { ParentPinPad("رمز الوالدين", check = { PinCheck.Wrong(3) }, onAccepted = {}) } }
        compose.waitForIdle()
        press(listOf(Key.One, Key.Two, Key.Three, Key.Four))
        compose.onNodeWithTag("pin_submit").assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithTag("pin_notice").assertExists()
        compose.onNodeWithTag("pin_key_5").assertIsFocused()
    }

    @Test
    fun eightDigitsAreTheLongestPin_andSubmitThemselves() {
        val state = PinPadState()
        val submitted = mutableListOf<String>()
        compose.setContent { ShashaTheme { PinPad(state, "رمز", onSubmit = { submitted += it }) } }
        compose.waitForIdle()
        press(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight))
        assertThat(submitted).containsExactly("12345678")
        press(Key.Nine) // nothing longer exists
        assertThat(state.digits).isEqualTo("12345678")
    }

    @Test
    fun aWrongPinSaysHowManyTriesAreLeft_thenTheLockoutCountsDown() {
        val answers = ArrayDeque(listOf<PinCheck>(PinCheck.Wrong(attemptsLeft = 2), PinCheck.Locked(remainingMillis = 4 * 60_000L + 30_000)))
        var accepted: String? = null
        compose.setContent {
            ShashaTheme { ParentPinPad("رمز الوالدين", check = { answers.removeFirst() }, onAccepted = { accepted = it }) }
        }
        compose.waitForIdle()

        press(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Enter))
        compose.onNodeWithTag("pin_notice").assertTextEquals("رمز غير صحيح. بقيت محاولتان.")
        dots(0) // cleared for the next try

        // From here time moves only when the test says so.
        compose.mainClock.autoAdvance = false
        press(listOf(Key.Four, Key.Three, Key.Two, Key.One, Key.Enter))
        repeat(3) { compose.mainClock.advanceTimeByFrame() } // the check, then the countdown starts
        compose.onNodeWithTag("pin_notice").assertTextEquals("محاولات خاطئة كثيرة. حاول بعد 4:30")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("pin_notice").assertTextEquals("محاولات خاطئة كثيرة. حاول بعد 4:29")
        // Locked out: keys do nothing.
        press(listOf(Key.Five, Key.Five))
        compose.mainClock.advanceTimeByFrame()
        dots(0)
        assertThat(accepted).isNull()
        // Once the lockout is over, the pad takes digits again.
        compose.mainClock.advanceTimeBy(270_000)
        compose.onNodeWithTag("pin_notice").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("pin_key_5").assertIsFocused()
        press(Key.Five)
        dots(1)
    }

    @Test
    fun theRightPinIsHandedOn() {
        var accepted: String? = null
        compose.setContent { ShashaTheme { ParentPinPad("رمز الوالدين", check = { if (it == "8080") PinCheck.Accepted else PinCheck.Wrong(4) }, onAccepted = { accepted = it }) } }
        compose.waitForIdle()
        press(listOf(Key.Eight, Key.Zero, Key.Eight, Key.Zero, Key.Enter))
        assertThat(accepted).isEqualTo("8080")
    }

    @Test
    fun aNewPinIsTypedTwice_andTwoDifferentOnesStartOver() {
        val chosen = mutableListOf<String>()
        compose.setContent { ShashaTheme { NewPinPad("اختر رمز الوالدين", onChosen = { chosen += it }) } }
        compose.waitForIdle()
        compose.onNodeWithTag("new_pin_enter").assertExists()
        press(listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Enter))
        compose.onNodeWithTag("new_pin_confirm").assertExists()
        press(listOf(Key.One, Key.Two, Key.Three, Key.Five, Key.Enter))
        compose.onNodeWithTag("new_pin_enter").assertExists()
        compose.onNodeWithTag("pin_notice").assertTextEquals("الرمزان غير متطابقين. اختر الرمز من جديد.")
        assertThat(chosen).isEmpty()

        press(listOf(Key.Five, Key.Five, Key.Six, Key.Six, Key.Enter))
        press(listOf(Key.Five, Key.Five, Key.Six, Key.Six, Key.Enter))
        assertThat(chosen).containsExactly("5566")
    }
}

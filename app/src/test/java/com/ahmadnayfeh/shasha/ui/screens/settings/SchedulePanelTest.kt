package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * «أوقات المشاهدة» with the remote alone: DOWN leaves the time parts for «حفظ» and «طوال اليوم»; a part
 * changes with UP and DOWN only after OK on it, and OK (or BACK) stops changing it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class SchedulePanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<Schedule?>()
    private var dismissed = 0

    private fun show(schedule: Schedule? = Schedule(7 * 60, 22 * 60)) {
        compose.setContent {
            ShashaTheme {
                ScheduleDialog("أوقات المشاهدة", schedule, rememberTimeTexts(), onSave = { saved += it }, onDismiss = { dismissed++ })
            }
        }
        compose.waitForIdle()
    }

    /** Keys go to the dialog's window, to whatever has the focus there. */
    private fun press(key: Key) {
        compose.onNodeWithTag("schedule_dialog").performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Keys pressed one after the other ([Key] is a value class: no varargs of it). */
    private fun press(keys: List<Key>) = keys.forEach { press(it) }

    private fun spinner(tag: String) = compose.onNodeWithTag(tag).onChildren().onFirst()

    private val editing = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "editing")

    @Test
    fun down_reachesSave_andOkSaves() {
        show()
        compose.onNodeWithTag("spinner_hour_من").assertIsFocused()
        press(Key.DirectionDown)
        compose.onNodeWithTag("schedule_save").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(saved).containsExactly(Schedule(7 * 60, 22 * 60))
    }

    @Test
    fun allDay_isReachableToo() {
        show()
        press(Key.DirectionDown)
        compose.onNodeWithTag("schedule_save").assertIsFocused()
        press(Key.DirectionLeft) // «طوال اليوم» lies to the left in Arabic
        compose.onNodeWithTag("schedule_all_day").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(saved).containsExactly(null)
    }

    @Test
    fun okStartsChanging_upAndDownChange_okStops_thenDownToSave() {
        show()
        // Not changing yet: UP and DOWN would move the focus.
        compose.onNodeWithTag("spinner_hour_من").assert(editing.not())
        press(Key.DirectionCenter)
        compose.onNodeWithTag("spinner_hour_من").assert(editing)
        press(listOf(Key.DirectionDown, Key.DirectionDown)) // 7 → 5
        compose.onNodeWithTag("spinner_hour_من").assertIsFocused()
        // Still changing, the next part: the minutes.
        press(listOf(Key.DirectionRight, Key.DirectionUp)) // 5:00 → 5:05
        compose.onNodeWithTag("spinner_minute_من").assert(editing)
        press(Key.DirectionCenter) // done
        compose.onNodeWithTag("spinner_minute_من").assert(editing.not())
        press(Key.DirectionDown)
        compose.onNodeWithTag("schedule_save").assertIsFocused()
        press(Key.DirectionCenter)
        assertThat(saved).containsExactly(Schedule(5 * 60 + 5, 22 * 60))
    }

    @Test
    fun back_stopsChanging_withoutClosingTheDialog() {
        show()
        press(listOf(Key.DirectionCenter, Key.DirectionUp)) // 7 → 8
        press(Key.Back)
        compose.onNodeWithTag("spinner_hour_من").assert(editing.not())
        assertThat(dismissed).isEqualTo(0)
        // UP and DOWN move again: down to «حفظ», which keeps the change.
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        assertThat(saved).containsExactly(Schedule(8 * 60, 22 * 60))
    }

    @Test
    fun aWindowOverMidnight_isSavedAsItWas() {
        show(Schedule(20 * 60, 6 * 60))
        compose.onNodeWithTag("spinner_hour_من").assertIsFocused()
        spinner("spinner_hour_من").assertTextEquals("8")
        press(listOf(Key.DirectionDown, Key.DirectionCenter))
        assertThat(saved).containsExactly(Schedule(20 * 60, 6 * 60))
    }
}

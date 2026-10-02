package com.ahmadnayfeh.shasha.ui.paging

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TV_1080P
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The remote only moves focus; the list scrolls to follow it. «Is the next page asked for when focus nears the end?»:
 * yes, once the last cards within [threshold] of the end come into view, once per list size, and again when the list grew
 * and the focus nears its new end.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class LoadMoreWhenNearEndTest {
    @get:Rule
    val compose = createComposeRule()

    private var loads = 0
    private var count by mutableIntStateOf(40)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    @Test
    fun aGrid_asksWhenTheFocusNearsTheEnd_andAgainAfterItGrew() {
        compose.setContent {
            val state = rememberLazyGridState()
            LoadMoreWhenNearEnd(state) { loads++ }
            // Four columns of 90 dp cards, four rows in view (16 cards).
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.size(width = 800.dp, height = 360.dp), state = state) {
                items(count) { index -> Box(Modifier.height(90.dp).fillMaxWidth().testTag("cell_$index").focusable()) }
            }
        }
        compose.waitForIdle()
        assertThat(loads).isEqualTo(0) // 40 cards, 16 in view: far from the end

        focus("cell_0")
        repeat(6) { press(Key.DirectionDown) } // row 6 (cell 24): the last card in view is about 27, the end is 39
        compose.onNodeWithTag("cell_24").assertIsFocused()
        assertThat(loads).isEqualTo(0)

        press(Key.DirectionDown) // row 7 (cell 28): the last in view reaches 31, within 8 of the end
        compose.onNodeWithTag("cell_28").assertIsFocused()
        assertThat(loads).isEqualTo(1)

        // The same list is not asked twice, however long the focus stays near its end.
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertThat(loads).isEqualTo(1)

        // The page arrives: 40 more cards, the end is far again; nearing the new end asks again.
        count = 80
        compose.waitForIdle()
        assertThat(loads).isEqualTo(1)
        repeat(10) { press(Key.DirectionDown) } // to row 17 (cell 68): the last in view reaches 71 = 79 - 8
        compose.onNodeWithTag("cell_68").assertIsFocused()
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun aShortList_asksAtOnce_untilItIsLongEnough() {
        // 12 cards: everything is in view and the end is near from the start (the screen is not full).
        count = 12
        compose.setContent {
            val state = rememberLazyGridState()
            LoadMoreWhenNearEnd(state) { loads++ }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.size(width = 800.dp, height = 360.dp), state = state) {
                items(count) { index -> Box(Modifier.height(90.dp).fillMaxWidth().testTag("cell_$index").focusable()) }
            }
        }
        compose.waitForIdle()
        assertThat(loads).isEqualTo(1)
        // The page brings 8 more: still short (20 cards, 16 in view): it asks again by itself.
        count = 20
        compose.waitForIdle()
        assertThat(loads).isEqualTo(2)
        // 60 cards: the end is far, nothing more is asked for until the focus gets there.
        count = 60
        compose.waitForIdle()
        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun aList_asksWhenTheFocusNearsTheEnd() {
        count = 30
        compose.setContent {
            val state = rememberLazyListState()
            LoadMoreWhenNearEnd(state) { loads++ }
            LazyColumn(Modifier.size(width = 400.dp, height = 360.dp), state = state) {
                items((0 until count).toList()) { index -> Box(Modifier.height(90.dp).fillMaxWidth().testTag("row_$index").focusable()) }
            }
        }
        compose.waitForIdle()
        assertThat(loads).isEqualTo(0) // 30 rows, 4 in view
        focus("row_0")
        repeat(23) { press(Key.DirectionDown) } // row 23: the last in view is 23, the end is 29, threshold 5: not yet
        compose.onNodeWithTag("row_23").assertIsFocused()
        assertThat(loads).isEqualTo(0)
        press(Key.DirectionDown) // row 24: the last in view is 24 = 29 - 5
        compose.onNodeWithTag("row_24").assertIsFocused()
        assertThat(loads).isEqualTo(1)
    }
}

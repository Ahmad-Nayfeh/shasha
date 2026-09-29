package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every settings pane can be read to its last line with the remote alone: entering it and walking DOWN through
 * its elements, each line of text comes fully into view at some point, clear of the pane's edge fades (a pane
 * scrolls only by moving the focus, so a line below the last focusable element could otherwise stay cut for
 * good). Beside the rail, at 1920x1080, with the household that has the most to show: everything unlocked, the
 * protection missing things, a measured return.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
// Real text measuring (lines wrap as on the TV): the heights, and so what fits, depend on it.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SettingsPaneReadingTest(private val category: SettingsCategory) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyLine_comesIntoView_withTheDpad() {
        val state = SettingsHubFixtures.state().copy(protection = SettingsHubFixtures.protectionMissing)
        compose.setContent {
            ShashaTheme {
                // Beside the rail, as on the TV: the pane's width decides how its lines wrap, and so its height.
                var selected by remember { mutableStateOf(category) }
                val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
                MainShell(Tab.SETTINGS, {}, itemFocus) {
                    SettingsHubScreen(state, selected, { selected = it }, SettingsHubActions(), remember { ContentFocus() })
                }
            }
        }
        compose.waitForIdle()
        press(Key.DirectionLeft)
        val everyLine = texts().map { it.first }.toSet()
        val read = mutableSetOf<String>()
        // Some panes take the focus in the middle (the current quality): up to their top first, then down to the end.
        walk(Key.DirectionUp, read)
        if (focusedInPane() == null) press(Key.DirectionLeft) // UP left the pane: back in, on its first element
        walk(Key.DirectionDown, read)
        assertWithMessage("lines of «${category.name}» never fully in view").that(everyLine - read).isEmpty()
    }

    /** Moves the focus [key] until it stops (or leaves the pane), noting every line wholly in view on the way. */
    private fun walk(key: Key, read: MutableSet<String>) {
        for (step in 0 until MAX_STEPS) {
            read += readable()
            val before = focusedInPane() ?: return
            press(key)
            val after = focusedInPane() ?: return
            if (after == before) {
                read += readable()
                return
            }
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        // Let the focus's scrolling animation finish.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private val inPane = hasAnyAncestor(hasTestTag(BODY))

    /** The pane's lines of text (their unclipped bounds: a line cut by the pane's edge still has its full size). */
    private fun texts(): List<Pair<String, androidx.compose.ui.unit.DpRect>> {
        val nodes = compose.onAllNodes(inPane and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().indices.map { i ->
            val node = nodes[i]
            val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ")
            text to node.getUnclippedBoundsInRoot()
        }
    }

    /** The lines now wholly inside the pane, and not under one of its fades. */
    private fun readable(): Set<String> {
        val body = compose.onNodeWithTag(BODY).getBoundsInRoot()
        val top = body.top + if (exists("pane_fade_top")) FADE else 0.dp
        val bottom = body.bottom - if (exists("pane_fade_bottom")) FADE else 0.dp
        return texts().filter { (_, bounds) -> bounds.top >= top - SLACK && bounds.bottom <= bottom + SLACK }.map { it.first }.toSet()
    }

    /** The focused element's tag while it is in the pane; null once the focus left it. */
    private fun focusedInPane(): String? {
        val focused = compose.onAllNodes(isFocused() and inPane, useUnmergedTree = true).fetchSemanticsNodes()
        return focused.firstOrNull()?.let { node -> node.config.getOrElse(SemanticsProperties.TestTag) { "#${node.id}" } }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    companion object {
        private const val BODY = "settings_pane_body"
        private const val MAX_STEPS = 40
        private val FADE = 24.dp // the part of a fade that really hides a line (it starts transparent)
        private val SLACK = 1.dp

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun categories(): List<Array<Any>> = SettingsCategory.entries.map { arrayOf<Any>(it) }
    }
}

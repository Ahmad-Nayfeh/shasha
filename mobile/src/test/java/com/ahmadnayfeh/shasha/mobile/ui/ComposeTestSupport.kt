package com.ahmadnayfeh.shasha.mobile.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/** A phone-sized screen for Robolectric (its default is a very small one). */
const val PHONE = "w411dp-h891dp-xxhdpi"

/** The same screen with the phone in dark mode (the qualifiers must come in Android's order: `night` before the density). */
const val PHONE_NIGHT = "w411dp-h891dp-night-xxhdpi"

fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/** A tab named [label] that is not in the bottom bar: a channel page's «Videos», «Shorts», «Playlists». */
fun SemanticsNodeInteractionsProvider.tab(label: String) = onNode(hasText(label) and hasRole(Role.Tab) and !hasAnyAncestor(hasTestTag(NAV_BAR_TAG)))

/** The bottom bar's tab named [label] (the same word may also be a title or a button elsewhere on the screen). */
fun SemanticsNodeInteractionsProvider.navTab(label: String) = onNode(hasText(label) and hasRole(Role.Tab) and hasAnyAncestor(hasTestTag(NAV_BAR_TAG)))

/** Opens Settings the way the viewer does: the «You» tab, then the gear of its top bar. */
fun ComposeTestRule.openSettings(youLabel: String, settingsLabel: String) {
    onNode(hasText(youLabel) and hasRole(Role.Tab) and hasAnyAncestor(hasTestTag(NAV_BAR_TAG))).performClick()
    onNode(hasContentDescription(settingsLabel) and hasClickAction()).performClick()
}

/** Opens the search page the way the viewer does: the magnifier of the Home's top bar. */
fun ComposeTestRule.openSearch(searchLabel: String) {
    onNode(hasContentDescription(searchLabel) and hasClickAction()).performClick()
}

/**
 * Waits until [text] is on the screen: what the screens show comes from Room and DataStore, which answer on their own
 * threads, outside the test clock.
 */
fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = 10_000, substring: Boolean = false) {
    waitUntil(timeoutMillis) {
        try {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        } catch (e: IllegalStateException) {
            false // no screen for a moment: the activity is being recreated
        }
    }
}

/**
 * Waits, in real time, until [condition] holds: for what Room, DataStore and the player's loader do on their own threads. The
 * test clock's `waitUntil` counts frames, which pass faster than a database transaction does.
 */
fun ComposeTestRule.awaitCondition(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Condition still not satisfied after $timeoutMillis ms" }
        Thread.sleep(20)
        waitForIdle()
    }
}

/** Whether this node is inside the bottom bar (its «Shorts» tab is not a Short). */
fun androidx.compose.ui.semantics.SemanticsNode.isInNavBar(): Boolean {
    var node: androidx.compose.ui.semantics.SemanticsNode? = this
    while (node != null) {
        if (node.config.contains(SemanticsProperties.TestTag) && node.config[SemanticsProperties.TestTag] == NAV_BAR_TAG) return true
        node = node.parent
    }
    return false
}

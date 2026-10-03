package com.ahmadnayfeh.shasha.mobile.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/** A phone-sized screen for Robolectric (its default is a very small one). */
const val PHONE = "w411dp-h891dp-xxhdpi"

/** The same screen with the phone in dark mode (the qualifiers must come in Android's order: `night` before the density). */
const val PHONE_NIGHT = "w411dp-h891dp-night-xxhdpi"

fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/** The bottom bar's tab named [label] (the same word may also be a title or a button elsewhere on the screen). */
fun SemanticsNodeInteractionsProvider.tab(label: String) = onNode(hasText(label) and hasRole(Role.Tab))

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

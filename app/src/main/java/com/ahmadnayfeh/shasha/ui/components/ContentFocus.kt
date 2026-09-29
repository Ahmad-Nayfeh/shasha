package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged

/**
 * Hands focus to a screen's content as soon as the content can take it: a TV screen always needs a
 * focused element, but lists arrive a moment after the screen appears.
 *
 * [pending] is set when the screen opens, when the user comes back to it, or when the rail passes
 * focus over; the content calls [TakeFocusWhenReady] and attaches [requester] to its focus group
 * (a grid with focusRestorer, so focus lands on the card it last had).
 */
@Stable
class ContentFocus(pending: Boolean = true) {
    val requester = FocusRequester()

    var pending by mutableStateOf(pending)
        private set

    /** Ask the content to take focus: [TakeFocusWhenReady] does it as soon as the content can. */
    fun request() {
        pending = true
    }

    fun cancel() {
        pending = false
    }
}

/** Attach to the content's focus group (before its focusRestorer, so a request restores the last card). */
fun Modifier.contentFocus(focus: ContentFocus): Modifier =
    onFocusChanged { if (it.hasFocus) focus.cancel() }.focusRequester(focus.requester)

/** Takes focus for [focus] once [ready] (e.g. the first page arrived) while a request is pending. */
@Composable
fun TakeFocusWhenReady(focus: ContentFocus, ready: Boolean) {
    LaunchedEffect(ready, focus.pending) {
        if (ready && focus.pending) {
            // Lazy lists compose their items during layout: wait one frame so there is something to focus.
            withFrameNanos { }
            focus.requester.requestFocus()
        }
    }
}

/**
 * A non-list screen area (an empty state, a settings page) that takes focus for [focus]: entering it
 * focuses [initial] the first time, and the last focused element after that.
 */
@Composable
fun FocusArea(
    focus: ContentFocus,
    initial: FocusRequester,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.contentFocus(focus).focusRestorer(initial).focusGroup(), contentAlignment = contentAlignment, content = content)
}

package com.ahmadnayfeh.shasha.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.messageRes
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens

/**
 * Why something may not be played or opened in this profile, said calmly (it is a rule, not a failure:
 * never the YouTube error screen). The sentence is the engine's reason ([messageRes]); «رجوع» has the focus,
 * [moreActions] (e.g. «إلغاء الاشتراك» for a blocked channel the adult follows) come after it.
 */
@Composable
fun PolicyNotice(reason: DenyReason, onBack: () -> Unit, modifier: Modifier = Modifier, moreActions: @Composable RowScope.() -> Unit = {}) {
    val back = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { back.requestFocus() }
    }
    val accent = LocalAccent.current
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.tint(0.10f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.32f),
                        radius = size.width * 0.4f,
                    ),
                )
            }
            .padding(ShashaDimens.ScreenPadding)
            .testTag("policy_notice"),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(reason.glyph(), reason.colors(), Modifier.size(width = 120.dp, height = 128.dp))
            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(reason.titleRes()),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("policy_notice_title"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(reason.messageRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShashaButton(
                    stringResource(R.string.action_back),
                    onBack,
                    icon = ShashaIcons.Back,
                    modifier = Modifier.focusRequester(back).testTag("policy_notice_back"),
                )
                moreActions()
            }
        }
    }
}

/** A short title per reason; the full sentence is [messageRes]. */
@StringRes
fun DenyReason.titleRes(): Int = when (this) {
    DenyReason.NOT_IN_ALLOWLIST -> R.string.deny_title_not_in_allowlist
    DenyReason.BLOCKED_CHANNEL -> R.string.deny_title_blocked_channel
    DenyReason.BLOCKED_KEYWORD -> R.string.deny_title_blocked_keyword
    DenyReason.SHORTS_HIDDEN -> R.string.deny_title_shorts_hidden
    DenyReason.TIME_UP -> R.string.deny_title_time_up
    DenyReason.OUTSIDE_SCHEDULE -> R.string.deny_title_outside_schedule
    DenyReason.LOCKED -> R.string.deny_title_locked
}

private fun DenyReason.glyph(): MarkGlyph = when (this) {
    DenyReason.TIME_UP -> MarkGlyph.HOURGLASS
    DenyReason.OUTSIDE_SCHEDULE -> MarkGlyph.MOON
    DenyReason.LOCKED -> MarkGlyph.LOCK
    else -> MarkGlyph.SHIELD
}

private fun DenyReason.colors(): List<Color> = when (this) {
    DenyReason.TIME_UP -> TimeColors
    DenyReason.OUTSIDE_SCHEDULE -> NightColors
    else -> RuleColors
}

/** The screen of the time-up mark: warm, like the end of a day. */
val TimeColors = listOf(Color(0xFFFFD27A), Color(0xFFEE9A3A))

/** The screen of the bedtime mark: a night sky. */
val NightColors = listOf(Color(0xFF8F87FF), Color(0xFF4B3FC4))

private val RuleColors = listOf(Color(0xFF7C8AA0), Color(0xFF4A5566))

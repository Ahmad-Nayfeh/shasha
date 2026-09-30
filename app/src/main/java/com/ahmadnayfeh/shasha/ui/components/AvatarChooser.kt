package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The avatars to choose from, in rows. The chosen one wears a ring and a check; focus enters on it.
 * [first] lets the caller move the focus here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarChooser(
    keys: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    first: FocusRequester = remember { FocusRequester() },
) {
    val accent = LocalAccent.current
    FlowRow(
        modifier.focusRestorer(first).focusGroup().testTag("avatar_chooser"),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        keys.forEach { key ->
            val chosen = key == selected
            // The check sits outside the round, clipped surface.
            Box(Modifier.size(size)) {
                Surface(
                    onClick = { onSelect(key) },
                    modifier = Modifier
                        .size(size)
                        .semantics { this.selected = chosen }
                        .then(if (chosen || (key == keys.first() && selected !in keys)) Modifier.focusRequester(first) else Modifier)
                        .testTag("avatar_$key"),
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = ShashaColors.Surface, focusedContainerColor = ShashaColors.Surface),
                    border = ClickableSurfaceDefaults.border(
                        border = if (chosen) Border(BorderStroke(3.dp, accent.main), inset = 3.dp, shape = CircleShape) else Border.None,
                        focusedBorder = Border(BorderStroke(4.dp, accent.main), inset = 2.dp, shape = CircleShape),
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
                    glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.cardGlow),
                ) {
                    ProfileAvatar(key, name, size, Modifier.align(Alignment.Center))
                }
                if (chosen) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(size * 0.34f)
                            .background(accent.main, CircleShape)
                            .border(2.dp, ShashaColors.Background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ShashaIcons.Check, contentDescription = null, tint = accent.onMain, modifier = Modifier.size(size * 0.24f))
                    }
                }
            }
        }
    }
}

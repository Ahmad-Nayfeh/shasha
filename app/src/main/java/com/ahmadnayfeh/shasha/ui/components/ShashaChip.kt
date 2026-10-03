package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * A filter pill: accent-tinted with a check when [selected], solid accent when focused. [large]: a bigger pill for what is
 * chosen first (a channel's sections), which marks the selected one by its tint alone, without the check.
 */
@Composable
fun ShashaChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, large: Boolean = false) {
    val shape = RoundedCornerShape(percent = 50)
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.tint(0.18f) else ShashaColors.SurfaceVariant,
            contentColor = if (selected) accent.bright else ShashaColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ShashaFocus.ButtonScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) accent.tint(0.6f) else ShashaColors.Outline), shape = shape),
            focusedBorder = Border.None,
        ),
    ) {
        val check = selected && !large
        Row(
            Modifier.padding(
                start = if (check) 14.dp else if (large) 24.dp else 22.dp,
                end = if (large) 24.dp else 22.dp,
                top = if (large) 10.dp else 8.dp,
                bottom = if (large) 10.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (check) {
                Icon(ShashaIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge)
        }
    }
}

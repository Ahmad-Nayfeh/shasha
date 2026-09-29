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
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** A filter pill: teal-tinted with a check when [selected], solid teal when focused. */
@Composable
fun ShashaChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) ShashaColors.Primary.copy(alpha = 0.18f) else ShashaColors.SurfaceVariant,
            contentColor = if (selected) ShashaColors.PrimaryBright else ShashaColors.Text,
            focusedContainerColor = ShashaColors.Primary,
            focusedContentColor = ShashaColors.OnPrimary,
            pressedContainerColor = ShashaColors.PrimaryBright,
            pressedContentColor = ShashaColors.OnPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = ShashaFocus.ButtonScale),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = ShashaFocus.ButtonGlow),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) ShashaColors.Primary.copy(alpha = 0.6f) else ShashaColors.Outline), shape = shape),
            focusedBorder = Border.None,
        ),
    ) {
        Row(
            Modifier.padding(start = if (selected) 14.dp else 22.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(ShashaIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

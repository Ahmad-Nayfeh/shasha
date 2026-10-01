package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.Accents

/** Round transport button (play/pause, ±10 s). The accent with a glow when focused, like everything else. */
@Composable
internal fun OsdIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    badge: String? = null,
) {
    val accent = Accents.overDark(LocalAccent.current)
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).semantics { this.contentDescription = contentDescription },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
        interactionSource = remember { MutableInteractionSource() },
    ) {
        Box(Modifier.align(Alignment.Center)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.54f).align(Alignment.Center))
            if (badge != null) {
                // "10" inside the circular arrow of the seek buttons.
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 8.sp),
                    modifier = Modifier.align(Alignment.Center).padding(top = 3.dp),
                )
            }
        }
    }
}

/** A labelled menu opener: small label on top ("الجودة"), current value under it ("تلقائي · 720p"). */
@Composable
internal fun OsdChip(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val accent = Accents.overDark(LocalAccent.current)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
        interactionSource = remember { MutableInteractionSource() },
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.75f))
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
        }
    }
}

/** Red "مباشر" pill for live streams. */
@Composable
internal fun LiveBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(OsdColors.Error, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/**
 * Amber "360p" pill (owner decision): only a low-quality muxed file of [height] plays, as YouTube serves "made for
 * kids" videos to this extractor. It is said on screen, never played silently at 360p.
 */
@Composable
internal fun LowQualityBadge(height: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val description = stringResource(R.string.player_low_quality, height.toString())
    Row(
        modifier
            .background(OsdColors.Secondary.copy(alpha = 0.16f), shape)
            .border(1.dp, OsdColors.Secondary.copy(alpha = 0.75f), shape)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("osd_low_quality"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${height}p", style = MaterialTheme.typography.labelLarge, color = OsdColors.Secondary)
    }
}

package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/** Dark pill over a thumbnail: a duration ("12:34") or a count, with an optional icon. */
@Composable
fun ThumbnailBadge(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

/** Red "مباشر" pill for live streams on thumbnails. */
@Composable
fun LiveThumbnailBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(ShashaColors.Error, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.badge_live), style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * How much of a video was watched, as a thin red bar (#FF0000, the colour viewers know this bar by) over a darker track
 * along the bottom of its thumbnail; a finished video shows the whole bar. The same bar on every tile that shows a
 * video with saved progress (history, home, search, channels, playlists, suggestions). Like the player's seek bar it
 * runs left to right (media time is not mirrored in RTL). [fraction] is read at draw time, so a changing value redraws
 * the bar without recomposing the card.
 */
@Composable
fun WatchedBar(fraction: () -> Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    // Canvas coordinates are never mirrored: x = 0 is the left edge in RTL too.
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(WatchedTrack)
        drawRect(WatchedRed, Offset.Zero, Size(size.width * fraction().coerceIn(0f, 1f), size.height))
    }
}

/** The watched part of [WatchedBar]: pure red, in both themes (it sits on the picture, not on the theme). */
val WatchedRed = Color(0xFFFF0033)

/** What is not watched yet: a dark veil, so the red reads on bright and dark pictures alike. */
private val WatchedTrack = Color(0xFF0B0F14).copy(alpha = 0.62f)

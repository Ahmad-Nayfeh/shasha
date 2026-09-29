package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.PlexArabic
import com.ahmadnayfeh.shasha.youtube.ChannelItem

/**
 * A channel's round picture. Channels imported from Takeout have none until their page is opened:
 * they get their first letter on a colour picked from the channel id (stable across screens).
 */
@Composable
fun ChannelAvatar(url: String?, name: String, channelId: String, size: Dp, modifier: Modifier = Modifier) {
    val (top, bottom) = remember(channelId) { AvatarColors[Math.floorMod(channelId.hashCode(), AvatarColors.size)] }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        val initial = remember(name) { name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "" }
        Text(
            initial,
            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = PlexArabic, fontSize = (size.value * 0.42f).sp, lineHeight = (size.value * 0.5f).sp),
            color = Color.White.copy(alpha = 0.92f),
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A channel in a grid, as tall as a video card: its picture centred in a 16:9 tile, its name and
 * [subtitle] (subscribers) under it. OK opens the channel.
 */
@Composable
fun ChannelCard(channel: ChannelItem, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tileModifier: Modifier = Modifier) {
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.SurfaceHigh,
            modifier = tileModifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize().background(ChannelTileBrush))
            ChannelAvatar(
                channel.avatarUrl,
                channel.name,
                channel.channelId,
                size = 72.dp,
                modifier = Modifier.align(Alignment.Center).border(2.dp, Color.White.copy(alpha = 0.10f), CircleShape),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            channel.name,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A playlist in a grid: its thumbnail with the video count, title and channel. OK opens it. */
@Composable
fun PlaylistCard(item: PlaylistUi, formats: Formats, onClick: () -> Unit, modifier: Modifier = Modifier, tileModifier: Modifier = Modifier) {
    val playlist = item.playlist
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.Surface,
            modifier = tileModifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Thumbnail(playlist.thumbnailUrl, Modifier.fillMaxSize(), CardShape, logoSize = 44.dp)
            val count = playlist.videoCount?.let { formats.count(R.array.count_videos, it) } ?: stringResource(R.string.playlist_label)
            ThumbnailBadge(count, Modifier.align(Alignment.BottomEnd).padding(8.dp), icon = ShashaIcons.PlaylistPlay)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl),
            textAlign = TextAlign.Right,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        MetaLine(playlist.channelName, stringResource(R.string.playlist_label))
    }
}

private val ChannelTileBrush = Brush.radialGradient(listOf(ShashaColors.SurfaceHigh, ShashaColors.Surface))

private val AvatarColors = listOf(
    Color(0xFF1FAE9F) to Color(0xFF0E5E57),
    Color(0xFFE59A2E) to Color(0xFF7A4B0C),
    Color(0xFF5B7BD5) to Color(0xFF263D7A),
    Color(0xFFD5586B) to Color(0xFF6E1F2C),
    Color(0xFF8E6BD5) to Color(0xFF41287A),
    Color(0xFF4FA35F) to Color(0xFF1F5129),
)

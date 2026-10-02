package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign
import kotlinx.coroutines.launch

/**
 * Shelves: a titled row of cards that scrolls sideways, as one full-width item of a [ShashaGrid] (the home's «مقترحة لك»
 * and «Shorts», a channel's Shorts). The row is wider than the grid by [ShelfBleed] on each side, with the same padding
 * inside, so its cards line up with the grid's columns and a focused card's zoom and glow are not cut at the edges.
 * LEFT and RIGHT move along the row; coming back to it focuses the card it had ([focusRestorer]). When the focus comes
 * into a shelf, the whole shelf scrolls into view: its title above and the titles under its cards, not only the card.
 */
object ShelfDefaults {
    /** A Short's tile is portrait (9:16) and at least this wide: six fit across the home, beside the rail. */
    val ShortCardMinWidth = 112.dp
}

/**
 * Room for a focused card's zoom and glow on either side of a shelf. As wide as the gap between two cards, no wider:
 * the card after the last whole one starts exactly at the row's edge and does not show as a sliver.
 */
val ShelfBleed = GridDefaults.ColumnGap

/**
 * How wide a Short's tile is in a shelf across [width] (the grid's width, padding included): as many tiles as fit at
 * [ShelfDefaults.ShortCardMinWidth], stretched to fill the line exactly, like the grid's cells.
 */
fun shortCardWidth(
    width: Dp,
    contentPadding: PaddingValues = GridDefaults.ContentPadding,
    gap: Dp = GridDefaults.ColumnGap,
): Dp {
    val columns = gridColumns(width, contentPadding, ShelfDefaults.ShortCardMinWidth, gap)
    return gridCellWidth(width, columns, contentPadding, gap)
}

/** How many columns [ShashaGrid] lays out in [width] (GridCells.Adaptive's own arithmetic), at least one. */
fun gridColumns(
    width: Dp,
    contentPadding: PaddingValues = GridDefaults.ContentPadding,
    minCellWidth: Dp = GridDefaults.MinCellWidth,
    gap: Dp = GridDefaults.ColumnGap,
): Int {
    val available = width - contentPadding.calculateLeftPadding(LayoutDirection.Ltr) - contentPadding.calculateRightPadding(LayoutDirection.Ltr)
    return ((available + gap) / (minCellWidth + gap)).toInt().coerceAtLeast(1)
}

/** The width of one of [columns] grid cells in [width]: a shelf's cards are as wide as the grid's. */
fun gridCellWidth(
    width: Dp,
    columns: Int,
    contentPadding: PaddingValues = GridDefaults.ContentPadding,
    gap: Dp = GridDefaults.ColumnGap,
): Dp {
    val available = width - contentPadding.calculateLeftPadding(LayoutDirection.Ltr) - contentPadding.calculateRightPadding(LayoutDirection.Ltr)
    return (available - gap * (columns - 1)) / columns
}

/** A section's title in a grid: the accent dot, the title, and a quieter line beside it. */
@Composable
fun ShelfTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = WatchedRed, modifier = Modifier.size(26.dp))
        } else {
            Box(Modifier.size(10.dp).background(LocalAccent.current.main, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        if (subtitle != null) {
            Spacer(Modifier.width(14.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A shelf of video cards ([VideoCard], [cardWidth] wide: a grid cell's width). [first], when given, is the card the
 * grid focuses first (the shelf is the first thing on the screen); otherwise the shelf keeps its own.
 */
@Composable
fun VideoShelf(
    title: String,
    subtitle: String?,
    items: List<VideoUi>,
    watched: State<Map<String, Float>>,
    formats: Formats,
    nowMs: Long,
    cardWidth: Dp,
    onPlay: (VideoUi) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    first: FocusRequester? = null,
) {
    Column(modifier.wholeInViewOnFocus()) {
        ShelfTitle(title, subtitle = subtitle, icon = icon)
        Spacer(Modifier.height(6.dp))
        ShelfRow(items, first) { index, item, cardFocus ->
            val id = item.video.videoId
            val fraction by remember(id, watched) { derivedStateOf { watched.value[id] } }
            VideoCard(
                item = item,
                watched = fraction,
                formats = formats,
                nowMs = nowMs,
                onClick = { onPlay(item) },
                modifier = Modifier.width(cardWidth),
                thumbnailModifier = if (index == 0) Modifier.focusRequester(cardFocus) else Modifier,
            )
        }
    }
}

/** A shelf of Shorts ([ShortCard]): portrait tiles, [cardWidth] wide ([shortCardWidth]). [first] as in [VideoShelf]. */
@Composable
fun ShortsShelf(
    title: String,
    subtitle: String?,
    items: List<VideoUi>,
    watched: State<Map<String, Float>>,
    cardWidth: Dp,
    onPlay: (VideoUi) -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
    showChannel: Boolean = true,
) {
    Column(modifier.wholeInViewOnFocus()) {
        ShelfTitle(title, subtitle = subtitle, icon = ShortsGlyph)
        Spacer(Modifier.height(6.dp))
        ShelfRow(items, first) { index, item, cardFocus ->
            val id = item.video.videoId
            val fraction by remember(id, watched) { derivedStateOf { watched.value[id] } }
            ShortCard(
                item = item,
                watched = fraction,
                onClick = { onPlay(item) },
                modifier = Modifier.width(cardWidth),
                thumbnailModifier = if (index == 0) Modifier.focusRequester(cardFocus) else Modifier,
                showChannel = showChannel,
            )
        }
    }
}

@Composable
private fun ShelfRow(items: List<VideoUi>, first: FocusRequester?, card: @Composable (index: Int, item: VideoUi, firstCard: FocusRequester) -> Unit) {
    val own = remember { FocusRequester() }
    val firstCard = first ?: own
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .bleed(ShelfBleed)
            .focusRestorer(firstCard)
            .testTag("shelf_row"),
        horizontalArrangement = Arrangement.spacedBy(GridDefaults.ColumnGap),
        contentPadding = PaddingValues(horizontal = ShelfBleed, vertical = 12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, _ -> "shelf_card" }) { index, item -> card(index, item, firstCard) }
    }
}

/**
 * When the focus comes into the shelf (not as it moves along it), the grid scrolls the whole shelf into view. The card
 * asks for itself too; the grid answers the larger request that fits, so the shelf's title and its cards' titles show
 * whole instead of the card alone at the screen's edge.
 */
@Composable
private fun Modifier.wholeInViewOnFocus(): Modifier {
    val whole = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return bringIntoViewRequester(whole).onFocusChanged { if (it.hasFocus) scope.launch { whole.bringIntoView() } }
}

/** Measures and draws [horizontal] wider on both sides than the space given (a shelf's bleed). */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val width = constraints.maxWidth + 2 * extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-extra, 0) }
}

/**
 * A Short in a shelf: a portrait thumbnail (its picture cropped to 9:16) with its length and the red watched bar, the
 * title on two lines and (outside its own channel's page) the channel. OK plays; holding OK or MENU opens the card's
 * menu, like [VideoCard].
 */
@Composable
fun ShortCard(
    item: VideoUi,
    watched: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailModifier: Modifier = Modifier,
    showChannel: Boolean = true,
) {
    val video = item.video
    val openMenu = LocalVideoMenu.current
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            onLongClick = { openMenu(video) },
            shape = CardShape,
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.Surface,
            scale = ShashaFocus.CardScale,
            modifier = thumbnailModifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .onMenuKey { openMenu(video) }
                .testTag("short_card"),
        ) {
            val url = remember(video.videoId) { Thumbnails.video(video.videoId) }
            Thumbnail(url, Modifier.fillMaxSize(), CardShape, logoSize = 40.dp)
            val duration = remember(video.durationSeconds) { video.durationSeconds?.let { formatDuration(it * 1000) } }
            if (duration != null) {
                ThumbnailBadge(duration, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (watched != null) 12.dp else 8.dp))
            }
            if (watched != null) WatchedBar({ watched }, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            video.title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
            textAlign = StartTextAlign,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showChannel) MetaLine(video.channelName, secondary = null)
    }
}

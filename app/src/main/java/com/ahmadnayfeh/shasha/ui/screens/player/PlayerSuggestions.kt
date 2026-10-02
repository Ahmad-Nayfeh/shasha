package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.components.CardShape
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShortsBadgeFor
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.components.ThumbnailBadge
import com.ahmadnayfeh.shasha.ui.components.WatchedBar
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlin.math.roundToInt

/** A suggestion's card over the video (the player's «مقترحة» row, the end card): dark in both themes. */
val SuggestionCardWidth = 196.dp

/**
 * «مقترحة»: suggested videos in one row that scrolls sideways, over the video. OK on a card plays it ([onPlay]; the
 * player decides what that means while the video is locked). [rowFocus] is attached to the row, so asking it for the
 * focus lands on the card the row last had (the first, the first time). [onFocused] says whether a card has the focus.
 */
@Composable
internal fun SuggestionStrip(
    videos: List<VideoItem>,
    watched: Map<String, Float>,
    onPlay: (VideoItem) -> Unit,
    rowFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onFocused: (Boolean) -> Unit = {},
) {
    val firstCard = remember { FocusRequester() }
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocused(it.hasFocus) }
            .focusRequester(rowFocus)
            .focusRestorer(firstCard)
            .testTag("suggestion_row"),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = ShashaDimens.ScreenHorizontal, vertical = StripPadding),
    ) {
        itemsIndexed(videos, key = { _, video -> video.videoId }) { index, video ->
            SuggestionCard(
                video = video,
                watched = watched[video.videoId],
                onClick = { onPlay(video) },
                modifier = Modifier.width(SuggestionCardWidth),
                thumbnailModifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
            )
        }
    }
}

/** One suggestion over the video: the thumbnail (length, «Shorts», the red watched bar), the title, the channel. */
@Composable
internal fun SuggestionCard(
    video: VideoItem,
    watched: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailModifier: Modifier = Modifier,
) {
    Column(modifier) {
        FocusSurface(
            onClick = onClick,
            shape = CardShape,
            containerColor = OsdColors.Surface,
            focusedContainerColor = OsdColors.Surface,
            modifier = thumbnailModifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("suggestion_card"),
        ) {
            val url = remember(video.videoId) { Thumbnails.video(video.videoId) }
            Thumbnail(url, Modifier.fillMaxSize(), CardShape, logoSize = 40.dp)
            val duration = remember(video.durationSeconds) { video.durationSeconds?.let { formatDuration(it * 1000) } }
            if (duration != null) {
                ThumbnailBadge(duration, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (watched != null) 12.dp else 8.dp))
            }
            ShortsBadgeFor(video, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (watched != null) WatchedBar({ watched }, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            video.title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
            color = OsdColors.Text,
            textAlign = StartTextAlign,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        video.channelName?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = OsdColors.Muted,
                textAlign = StartTextAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** «مقترحة» above the row; while the row is folded away, the arrow says DOWN opens it. */
@Composable
internal fun SuggestionsTitle(unfolded: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = TitleHeight).padding(horizontal = ShashaDimens.ScreenHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.player_suggested), style = MaterialTheme.typography.titleSmall, color = OsdColors.Text)
        Spacer(Modifier.width(6.dp))
        val folded by animateFloatAsState(if (unfolded) 0f else 1f, tween(220), label = "suggestionsArrow")
        // The chevron (mirrored in RTL) turned to point down: DOWN opens the row. It fades once the row is open.
        val down = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
        Icon(
            ShashaIcons.Chevron,
            contentDescription = null,
            tint = OsdColors.Muted,
            modifier = Modifier.size(22.dp).rotate(down).alpha(folded),
        )
    }
}

/**
 * The controls with «مقترحة» under them. Folded ([unfolded] false, no card focused), the controls stay exactly where they
 * always are and only the row's title shows under them; the row waits below the screen's edge, where DOWN still reaches
 * it. Unfolded (a suggestion has the focus), everything rises so the whole row shows above the bottom margin. The veil
 * behind ([controls]' own gradient, at its height when folded) grows with it.
 */
@Composable
internal fun SuggestionsDock(
    unfolded: Boolean,
    controls: @Composable () -> Unit,
    title: @Composable () -> Unit,
    row: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(if (unfolded) 1f else 0f, tween(280), label = "suggestionsDock")
    Layout(
        contents = listOf(controls, title, row),
        modifier = modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))) }
            .testTag("suggestions_dock"),
    ) { (controlsPart, titlePart, rowPart), constraints ->
        val full = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
        val c = controlsPart.first().measure(full)
        val t = titlePart.first().measure(full)
        val r = rowPart.first().measure(full)
        val titleTop = c.height + TitleGap.roundToPx()
        val rowTop = titleTop + t.height + RowGap.roundToPx()
        val folded = titleTop + t.height + FoldedMargin.roundToPx()
        val open = rowTop + r.height + OpenMargin.roundToPx()
        layout(constraints.maxWidth, (folded + (open - folded) * reveal).roundToInt()) {
            c.place(0, 0)
            t.place(0, titleTop)
            r.place(0, rowTop)
        }
    }
}

/** Above and below the cards in the row: room for a focused card's zoom and glow. */
private val StripPadding: Dp = 12.dp

/**
 * Between the controls and «مقترحة», its height, and under it while folded: together the bottom margin the controls
 * always had (ShashaDimens.ScreenVertical, 48 dp), so they do not move when there are suggestions.
 */
private val TitleGap: Dp = 6.dp
private val TitleHeight: Dp = 26.dp
private val FoldedMargin: Dp = 16.dp

/** Between «مقترحة» and the row: folded, its cards wait just under the screen's edge, not as a sliver above it. */
private val RowGap: Dp = FoldedMargin - StripPadding + 2.dp
private val OpenMargin: Dp = 28.dp

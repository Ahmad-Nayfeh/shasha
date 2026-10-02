package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.isShort
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The Shorts glyph: a portrait screen with a play triangle cut out of it (the triangle winds the other way, so it stays
 * empty). Drawn here on the 24x24 grid like [ShashaIcons]; no brand logo is used.
 */
val ShortsGlyph: ImageVector = ImageVector.Builder(
    name = "Shorts",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes("M9,2h6c1.1,0 2,0.9 2,2v16c0,1.1 -0.9,2 -2,2H9c-1.1,0 -2,-0.9 -2,-2V4c0,-1.1 0.9,-2 2,-2zM10.25,8.25v7.5l5.25,-3.75z"),
    fill = SolidColor(Color.Black),
).build()

/**
 * «Shorts» on a Short's thumbnail: a small dark pill with the red glyph, the same in both languages (laid out left to
 * right, like the word). Over the picture, so it keeps its colours in the light theme too.
 */
@Composable
fun ShortsBadge(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                .padding(start = 4.dp, end = 7.dp)
                .testTag("shorts_badge"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShortsGlyph, contentDescription = null, tint = WatchedRed, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text(stringResource(R.string.badge_shorts), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
        }
    }
}

/**
 * [ShortsBadge] when [video] is a Short under the parent's limit ([LocalBehavior]'s «حدّ الفيديو القصير»), nothing
 * otherwise: the engine's own rule ([isShort]), so every tile the rules would hide where shorts are hidden is badged
 * where they show.
 */
@Composable
fun ShortsBadgeFor(video: VideoItem, modifier: Modifier = Modifier) {
    val limit = LocalBehavior.current.shortsMaxSeconds.toLong()
    val short = remember(video, limit) { video.isShort(limit) }
    if (short) ShortsBadge(modifier)
}

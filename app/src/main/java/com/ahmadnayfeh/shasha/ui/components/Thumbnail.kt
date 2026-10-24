package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * A video thumbnail over a branded placeholder (a faint logo on a dark gradient), which is also what
 * stays visible when there is no picture or it fails to load. Once the picture is in, the placeholder is
 * no longer drawn under it: a screen of cards is then one layer per card, not three (a weak TV's GPU
 * feels every layer while a row scrolls).
 */
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    showLogo: Boolean = true,
    logoSize: Dp = 120.dp,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .then(if (loaded) Modifier else Modifier.background(PlaceholderBrush)),
        contentAlignment = Alignment.Center,
    ) {
        if (showLogo && !loaded) {
            Image(
                painterResource(R.drawable.splash_logo),
                contentDescription = null,
                modifier = Modifier.size(logoSize).alpha(0.22f),
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val PlaceholderBrush: Brush get() = Brush.linearGradient(listOf(ShashaColors.SurfaceHigh, ShashaColors.Surface))

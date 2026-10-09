package com.ahmadnayfeh.shasha.mobile.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.youtube.Storyboard
import kotlin.math.roundToInt

/** The storyboards' frames the preview asks for: sharp at its size, without loading sheets of big pictures. */
internal const val PREVIEW_MAX_FRAME_WIDTH = 200

private val PREVIEW_WIDTH = 160.dp

/**
 * What the seek bar shows above the finger while it is dragged: the frame of the video at [positionMs] (from its [storyboard], the
 * small pictures YouTube keeps of its frames) with the time under it. Without a storyboard (a downloaded file, a video YouTube has
 * none for), or until its sheet has loaded, the time alone.
 */
@Composable
internal fun SeekPreview(storyboard: Storyboard?, positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    val frame = storyboard?.frameAt(positionMs, durationMs)
    val sheet = frame?.let { rememberSheet(it.url) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (frame != null && storyboard != null) {
            val height: Dp = PREVIEW_WIDTH * (storyboard.frameHeight.toFloat() / storyboard.frameWidth)
            Box(
                Modifier.size(PREVIEW_WIDTH, height).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                    .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(6.dp)),
            ) {
                val image = sheet?.takeIf { it.first == frame.url }?.second
                if (image != null) {
                    Canvas(Modifier.size(PREVIEW_WIDTH, height)) {
                        // The cell of the sheet, kept inside the picture (the last sheet of a video can be smaller than a full one).
                        val x = frame.x.coerceIn(0, (image.width - 1).coerceAtLeast(0))
                        val y = frame.y.coerceIn(0, (image.height - 1).coerceAtLeast(0))
                        val w = frame.width.coerceAtMost(image.width - x)
                        val h = frame.height.coerceAtMost(image.height - y)
                        if (w > 0 && h > 0) {
                            drawImage(
                                image,
                                srcOffset = IntOffset(x, y),
                                srcSize = IntSize(w, h),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            )
                        }
                    }
                }
            }
        }
        Text(
            Formatters.clock(positionMs),
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Places what it is applied to above the seek bar's thumb: centred on [fraction] of a bar that spans the width less
 * [barInset] on each side, never past the edges. Left to right in every language, like the bar.
 */
internal fun Modifier.aboveThumb(fraction: Float, barInset: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val width = constraints.maxWidth
    val inset = barInset.roundToPx()
    val center = inset + fraction.coerceIn(0f, 1f) * (width - 2 * inset)
    val x = (center - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
    layout(width, placeable.height) { placeable.place(x, 0) }
}

/** One sheet of a storyboard, loaded once through the app's image loader (and its caches): the url it is for, and its pixels. */
@Composable
private fun rememberSheet(url: String): Pair<String, ImageBitmap>? {
    val context = LocalContext.current
    val sheet by produceState<Pair<String, ImageBitmap>?>(null, url) {
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.let { value = url to it }
    }
    return sheet
}

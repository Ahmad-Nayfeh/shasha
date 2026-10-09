package com.ahmadnayfeh.shasha.youtube

/**
 * One of a video's storyboards: the small pictures of its frames that YouTube shows above the seek bar while it is dragged. They
 * come in sheets ([urls], one image each): a grid of [framesPerPageX] × [framesPerPageY] frames of [frameWidth] × [frameHeight]
 * pixels, read left to right and top to bottom, one frame every [durationPerFrameMs], [totalCount] frames in all. A video has
 * several, from a few tiny frames to many bigger ones ([StreamDetails.storyboards], smallest frames first).
 */
data class Storyboard(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val framesPerPageX: Int,
    val framesPerPageY: Int,
    val totalCount: Int,
    /** 0 or less when YouTube did not say: [frameAt] then spreads the frames over the video's length. */
    val durationPerFrameMs: Int,
) {
    /** A storyboard that can be drawn at all: something to load and a grid that makes sense. */
    val isUsable: Boolean
        get() = urls.isNotEmpty() && frameWidth > 0 && frameHeight > 0 && framesPerPageX > 0 && framesPerPageY > 0 && totalCount > 0

    /**
     * The frame shown at [positionMs] of a video of [durationMs] (needed only when [durationPerFrameMs] is unknown): the sheet to
     * load and where the frame is in it. Null for a storyboard that is not [isUsable] or a position that cannot be placed.
     */
    fun frameAt(positionMs: Long, durationMs: Long = 0): StoryboardFrame? {
        if (!isUsable) return null
        val step = durationPerFrameMs.toLong().takeIf { it > 0 }
            ?: (durationMs / totalCount).takeIf { it > 0 }
            ?: return null
        val index = (positionMs.coerceAtLeast(0) / step).coerceAtMost(totalCount - 1L).toInt()
        val perPage = framesPerPageX * framesPerPageY
        val page = index / perPage
        val url = urls.getOrNull(page) ?: return null
        val inPage = index % perPage
        return StoryboardFrame(
            url = url,
            x = inPage % framesPerPageX * frameWidth,
            y = inPage / framesPerPageX * frameHeight,
            width = frameWidth,
            height = frameHeight,
        )
    }
}

/** One frame of a [Storyboard]: the rectangle at ([x], [y]) of [width] × [height] pixels in the sheet at [url]. */
data class StoryboardFrame(val url: String, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The storyboard to show while seeking, from a video's [StreamDetails.storyboards]: the one with the biggest frames that are at
 * most [maxFrameWidth] pixels wide (sharp enough, without loading sheets of big pictures), else the smallest there is. Null when
 * the video has none that can be drawn.
 */
fun List<Storyboard>.forPreview(maxFrameWidth: Int): Storyboard? {
    val usable = filter { it.isUsable }
    return usable.filter { it.frameWidth <= maxFrameWidth }.maxByOrNull { it.frameWidth } ?: usable.minByOrNull { it.frameWidth }
}

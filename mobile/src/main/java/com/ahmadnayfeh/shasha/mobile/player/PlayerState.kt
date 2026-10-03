package com.ahmadnayfeh.shasha.mobile.player

import androidx.compose.runtime.Immutable
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SkipNotice
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorSegment
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem

enum class PlayerPhase {
    /** Nothing is loaded. */
    IDLE,

    /** The video's streams are being found. */
    LOADING,

    /** A video is loaded (it may be buffering, paused or playing). */
    READY,

    /** The video reached its end. */
    ENDED,

    /** It cannot be played: [PlayerUiState.error] says why. */
    ERROR,
}

/** One quality the video can play at, and where it is among the player's tracks (for pinning it). */
@Immutable
data class QualityOption(
    /** YouTube's quality label: the short side of the picture. */
    val height: Int,
    val fps: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val bitrate: Int,
    /** Lower is preferred when several tracks have the same height (H.264 first: every phone decodes it). */
    val codecRank: Int,
)

/** What the player screen shows; the [PlayerController] keeps it up to date. */
@Immutable
data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    /** What is loaded: the preview the viewer tapped first, replaced by the extraction's details. */
    val video: VideoItem? = null,
    val details: StreamDetails? = null,
    val description: String? = null,
    /** A downloaded file is playing, not a stream. */
    val isOffline: Boolean = false,
    /** The offline file has no picture (an audio download). */
    val isAudioOnly: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val speed: Float = 1f,
    val qualities: List<QualityOption> = emptyList(),
    /** The height the viewer (or the default quality) pinned; null: automatic. */
    val pinnedQuality: Int? = null,
    /** The height of the picture on screen now. */
    val playingHeight: Int? = null,
    /** Set when only a muxed file of this height (≤ 360) exists: the screen says so. */
    val lowQualityHeight: Int? = null,
    /** The SponsorBlock segments in force (the selected categories). */
    val segments: List<SponsorSegment> = emptyList(),
    val skipNotice: SkipNotice? = null,
    /** Width / height of the picture. */
    val aspectRatio: Float = DEFAULT_ASPECT,
    val error: UiError? = null,
) {
    val isVideoShown: Boolean get() = phase == PlayerPhase.READY || phase == PlayerPhase.ENDED

    companion object {
        const val DEFAULT_ASPECT = 16f / 9f
    }
}

/** The speeds the player offers: 0.5× to 4×. */
object PlaybackSpeeds {
    val ALL = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    const val MIN = 0.5f
    const val MAX = 4f

    /** "1×", "1.25×", "0.5×". */
    fun label(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')
        return "$text×"
    }
}

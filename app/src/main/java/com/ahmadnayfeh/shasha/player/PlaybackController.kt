package com.ahmadnayfeh.shasha.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One ExoPlayer for one screen visit. Created when the player screen starts and [release]d when it
 * stops (app in the background, another screen on top) or leaves: a TV has little memory and one
 * hardware decoder to share.
 *
 * Quality: the MediaSource holds every decodable quality up to [MAX_AUTO_HEIGHT] (the cap is applied
 * inside the generated DASH manifest by the YouTube module, on the short side so vertical videos are
 * not cut down), and ExoPlayer adapts between them ("Auto"). Live manifests come from YouTube uncapped,
 * so the track selector applies the same cap ([withAutoQualityCap]). A fixed quality is a
 * TrackSelectionOverride on the same source, so switching does not reload the video.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val factory: YouTubeMediaSourceFactory,
    private val stream: StreamDetails,
    private val onError: (SourceException) -> Unit,
) {
    private val trackSelector = DefaultTrackSelector(context)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus= */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()
        .apply { trackSelectionParameters = trackSelectionParameters.withAutoQualityCap() }

    private val _state = MutableStateFlow(
        PlayerUiState(
            title = stream.video.title,
            channelName = stream.video.channelName,
            isLive = stream.video.isLive,
            subtitleOptions = subtitleOptions(stream.subtitles),
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Subtitle language baked into the current MediaSource (the factory merges one language). */
    private var sourceSubtitle: String? = null
    private var released = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(isPlaying = isPlaying) }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            _state.update { it.copy(playWhenReady = playWhenReady) }

        override fun onPlaybackStateChanged(playbackState: Int) = _state.update {
            it.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE,
                isEnded = playbackState == Player.STATE_ENDED,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            val sides = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { group.getTrackFormat(it).shortSide() }
            }
            _state.update { it.copy(qualityOptions = qualityOptions(sides)) }
            // A fixed quality chosen before the tracks were known (e.g. restored after the app was in the
            // background) can only be applied now.
            val choice = _state.value.quality
            if (choice is QualityChoice.Fixed && !hasVideoOverride(tracks)) applyQuality(choice)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val side = minOf(videoSize.width, videoSize.height)
            _state.update { it.copy(currentHeight = if (side > 0) qualityRung(side) else null) }
        }

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            _state.update { it.copy(cueText = text.ifEmpty { null }) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            _state.update { it.copy(speed = playbackParameters.speed) }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Not a failure: we fell behind the live window. Jump to the live edge and carry on.
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            onError(factory.classifyPlaybackError(error))
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * Loads the stream and starts (or resumes) playback.
     * @throws SourceException when the stream has nothing playable (the caller shows the error screen).
     */
    fun start(resume: ResumeState) {
        _state.update {
            it.copy(
                quality = resume.quality,
                speed = resume.speed,
                subtitleLanguage = resume.subtitleLanguage,
                playWhenReady = resume.playWhenReady,
            )
        }
        load(resume.subtitleLanguage, startPositionMs = if (stream.video.isLive) C.TIME_UNSET else resume.positionMs)
        player.playbackParameters = PlaybackParameters(resume.speed)
        player.playWhenReady = resume.playWhenReady
        player.prepare()
    }

    /** Stops and frees the decoder. Returns where to continue next time. */
    fun release(): ResumeState {
        val s = _state.value
        val resume = ResumeState(
            positionMs = if (player.isCurrentMediaItemLive) 0 else player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            quality = s.quality,
            speed = s.speed,
            subtitleLanguage = s.subtitleLanguage,
        )
        if (!released) {
            released = true
            player.removeListener(listener)
            player.release()
        }
        return resume
    }

    fun progress(): PlaybackProgress {
        if (released) return PlaybackProgress.Zero
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        return PlaybackProgress(player.currentPosition.coerceAtLeast(0), player.bufferedPosition.coerceAtLeast(0), duration)
    }

    fun onAction(action: PlayerAction) {
        if (released) return
        when (action) {
            PlayerAction.TogglePlayPause -> when {
                player.playbackState == Player.STATE_ENDED -> {
                    player.seekTo(0)
                    player.play()
                }
                player.playWhenReady -> player.pause()
                else -> player.play()
            }
            PlayerAction.Play -> player.play()
            PlayerAction.Pause -> player.pause()
            is PlayerAction.SeekTo -> {
                val duration = player.duration
                val target = if (duration != C.TIME_UNSET) action.positionMs.coerceIn(0, duration) else action.positionMs.coerceAtLeast(0)
                player.seekTo(target)
            }
            is PlayerAction.SetQuality -> {
                _state.update { it.copy(quality = action.choice) }
                applyQuality(action.choice)
            }
            is PlayerAction.SetSpeed -> player.playbackParameters = PlaybackParameters(action.speed)
            is PlayerAction.SetSubtitle -> setSubtitle(action.languageCode)
        }
    }

    private fun load(subtitleLanguage: String?, startPositionMs: Long) {
        try {
            val source = factory.create(stream, MAX_AUTO_HEIGHT, subtitleLanguage)
            sourceSubtitle = subtitleLanguage
            player.setMediaSource(source, startPositionMs)
            setTextEnabled(subtitleLanguage != null)
        } catch (e: RuntimeException) {
            throw e.asSourceException()
        }
    }

    private fun setSubtitle(languageCode: String?) {
        _state.update { it.copy(subtitleLanguage = languageCode, cueText = null) }
        when {
            languageCode == null -> setTextEnabled(false)
            languageCode == sourceSubtitle -> setTextEnabled(true)
            else -> {
                // Another language needs another MediaSource: reload at the same position.
                val position = if (player.isCurrentMediaItemLive) C.TIME_UNSET else player.currentPosition
                try {
                    load(languageCode, position)
                    player.prepare()
                } catch (e: SourceException) {
                    onError(e)
                }
            }
        }
    }

    private fun setTextEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    /** An override only counts while its track group still exists (a reload creates new groups). */
    private fun hasVideoOverride(tracks: Tracks): Boolean =
        player.trackSelectionParameters.overrides.values.any { override ->
            override.type == C.TRACK_TYPE_VIDEO && tracks.groups.any { it.mediaTrackGroup == override.mediaTrackGroup }
        }

    private fun applyQuality(choice: QualityChoice) {
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (choice is QualityChoice.Fixed) {
            // Prefer the adaptation set that is playing now (same codec: no decoder switch), then any.
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                .sortedByDescending { it.isSelected }
            for (group in groups) {
                val index = (0 until group.length).firstOrNull {
                    group.isTrackSupported(it) && qualityRung(group.getTrackFormat(it).shortSide()) == choice.height
                } ?: continue
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                break
            }
        }
        player.trackSelectionParameters = params.build()
    }

    private fun androidx.media3.common.Format.shortSide(): Int =
        if (width > 0 && height > 0) minOf(width, height) else height
}

/**
 * "Auto" never goes above 1080p, whatever the source (live HLS/DASH manifests list up to 2160p).
 *
 * Expressed as a 1920x1080 viewport that may rotate, not as setMaxVideoSize(.., 1080): ExoPlayer keeps the
 * smallest track that fills the viewport turned to the video's orientation, and nothing above it. So a
 * vertical 1080x1920 video keeps its 1080p track (a height limit would drop it to 480p), and an ultra-wide
 * one stops at the track that fills the screen's width (e.g. 1920x804). It also replaces Media3's default
 * viewport, the physical display, which is 4K on many TVs.
 */
internal fun TrackSelectionParameters.withAutoQualityCap(): TrackSelectionParameters =
    buildUpon()
        .setViewportSize(AUTO_VIEWPORT_WIDTH, MAX_AUTO_HEIGHT, /* viewportOrientationMayChange= */ true)
        .build()

private const val AUTO_VIEWPORT_WIDTH = 1920

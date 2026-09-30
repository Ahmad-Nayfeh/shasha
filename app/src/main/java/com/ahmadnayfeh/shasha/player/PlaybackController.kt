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
import androidx.media3.exoplayer.SeekParameters
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
 * Quality: the MediaSource holds every decodable quality up to [maxQuality] (Settings → «الجودة الافتراضية»,
 * [MAX_AUTO_HEIGHT] by default; the cap is applied inside the generated DASH manifest by the YouTube module,
 * on the short side so vertical videos are not cut down), and ExoPlayer adapts between them ("Auto"). Live
 * manifests come from YouTube uncapped, so the track selector applies the same cap ([withAutoQualityCap]). A
 * fixed quality is a TrackSelectionOverride on the same source, so switching does not reload the video.
 *
 * [onJump] hears every jump of the position (a seek from anywhere: the controls, the phone, a replay, the
 * adult's «تراجع عن التخطي»), from where to where: what was skipped was not played ([PlayedMeter], SPEC §4).
 * SponsorBlock's own skips ([seekExactly] with `automatic`) go to [onAutoSkip] instead: nobody chose them.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val factory: YouTubeMediaSourceFactory,
    private val stream: StreamDetails,
    private val maxQuality: Int = MAX_AUTO_HEIGHT,
    private val onJump: (fromMs: Long, toMs: Long) -> Unit = { _, _ -> },
    private val onAutoSkip: (fromMs: Long, toMs: Long) -> Unit = onJump,
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
        .apply { trackSelectionParameters = trackSelectionParameters.withAutoQualityCap(maxQuality) }

    private val _state = MutableStateFlow(
        PlayerUiState(
            title = stream.video.title,
            channelName = stream.video.channelName,
            isLive = stream.video.isLive,
            autoQualityCap = maxQuality,
            subtitleOptions = subtitleOptions(stream.subtitles),
            lowQualityHeight = stream.lowQualityHeight,
        ),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Subtitle language baked into the current MediaSource (the factory merges one language). */
    private var sourceSubtitle: String? = null
    private var released = false

    /** Set while [seekExactly] makes SponsorBlock's own jump: its discontinuity goes to [onAutoSkip]. */
    private var automaticSeek = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(isPlaying = isPlaying) }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
            signalPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE,
                    isEnded = playbackState == Player.STATE_ENDED,
                )
            }
            signalPlaying()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val sides = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { group.getTrackFormat(it).shortSide() }
            }
            _state.update { it.copy(qualityOptions = qualityOptions(sides, maxQuality)) }
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

        // Every reason, not only seeks: whatever moved the position was not played through.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (stream.video.isLive) return
            if (automaticSeek && reason == Player.DISCONTINUITY_REASON_SEEK) {
                automaticSeek = false
                onAutoSkip(oldPosition.positionMs, newPosition.positionMs)
            } else {
                onJump(oldPosition.positionMs, newPosition.positionMs)
            }
        }

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

    /** Where playback is now and the choices made: what a new player continues from. */
    fun resumeState(): ResumeState {
        val s = _state.value
        return ResumeState(
            positionMs = if (player.isCurrentMediaItemLive) 0 else player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            quality = s.quality,
            speed = s.speed,
            subtitleLanguage = s.subtitleLanguage,
        )
    }

    /** Stops and frees the decoder. Returns where to continue next time. */
    fun release(): ResumeState {
        val resume = resumeState()
        if (!released) {
            released = true
            player.removeListener(listener)
            player.release()
            PlayingSignal.update(this, wantsToPlay = false)
        }
        return resume
    }

    /**
     * Tells the app a video is playing (SPEC §2: the feed sync stands down meanwhile): while it wants to
     * play, buffering included; not when paused, ended, failed (idle) or released.
     */
    private fun signalPlaying() {
        val state = player.playbackState
        PlayingSignal.update(this, player.playWhenReady && (state == Player.STATE_BUFFERING || state == Player.STATE_READY))
    }

    fun progress(): PlaybackProgress {
        if (released) return PlaybackProgress.Zero
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        return PlaybackProgress(
            positionMs = player.currentPosition.coerceAtLeast(0),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
            durationMs = duration,
            isLive = stream.video.isLive || player.isCurrentMediaItemLive,
        )
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

    /**
     * Jumps to exactly [positionMs] (SponsorBlock: to a segment's end, or back to its start). EXACT only for this
     * jump: a seek that snapped to a keyframe would land inside the segment it skips. [automatic]: SponsorBlock's
     * own skip, reported to [onAutoSkip]; the adult's undo is an ordinary jump ([onJump]).
     */
    fun seekExactly(positionMs: Long, automatic: Boolean = false) {
        if (released) return
        val before = player.seekParameters
        player.setSeekParameters(SeekParameters.EXACT)
        // ExoPlayer tells its listeners about the seek before seekTo returns (this is never called from one of
        // them), so the flag covers exactly this jump; it never outlives the call.
        automaticSeek = automatic
        try {
            player.seekTo(positionMs.coerceAtLeast(0))
        } finally {
            automaticSeek = false
        }
        // Applied in order on the playback thread: the seek above still uses EXACT.
        player.setSeekParameters(before)
    }

    private fun load(subtitleLanguage: String?, startPositionMs: Long) {
        try {
            val source = factory.create(stream, maxQuality, subtitleLanguage)
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
 * "Auto" never goes above [maxQuality] (1080p unless Settings chose another), whatever the source (live
 * HLS/DASH manifests list up to 2160p).
 *
 * Expressed as a 16:9 viewport that may rotate (1920x1080 for 1080p), not as setMaxVideoSize(.., 1080):
 * ExoPlayer keeps the smallest track that fills the viewport turned to the video's orientation, and nothing
 * above it. So a vertical 1080x1920 video keeps its 1080p track (a height limit would drop it to 480p), and
 * an ultra-wide one stops at the track that fills the screen's width (e.g. 1920x804). It also replaces
 * Media3's default viewport, the physical display, which is 4K on many TVs.
 */
internal fun TrackSelectionParameters.withAutoQualityCap(maxQuality: Int = MAX_AUTO_HEIGHT): TrackSelectionParameters =
    buildUpon()
        .setViewportSize(viewportWidth(maxQuality), maxQuality, /* viewportOrientationMayChange= */ true)
        .build()

/** The 16:9 width of a quality (rounded like YouTube's own sizes: 480p is 854 wide, 1080p 1920). */
internal fun viewportWidth(height: Int): Int = (height * 16 + 8) / 9

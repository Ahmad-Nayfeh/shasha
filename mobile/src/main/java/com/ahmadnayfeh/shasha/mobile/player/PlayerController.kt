package com.ahmadnayfeh.shasha.mobile.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.watchUrl
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SkipController
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SkipTarget
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorBlockRepository
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorSegment
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.matching
import com.ahmadnayfeh.shasha.youtube.perLanguage
import com.ahmadnayfeh.shasha.youtube.playback.DecoderRetry
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The one player of the app (one ExoPlayer per process, so the sound goes on when the screen is left), with everything
 * around it: finding the streams ([VideoSource.stream]), building the Media3 source ([YouTubeMediaSourceFactory]),
 * resuming where the viewer stopped, SponsorBlock's automatic skips, quality, speed, and the state the player screen
 * shows ([state]). The [PlaybackService] gives the same player a media session and a notification.
 *
 * The same player also serves the Shorts viewer ([ShortsPlayer], [PlaybackMode.SHORT]): what differs for a Short is listed in
 * [PlaybackMode] and kept to the few places that need it, so the extraction, the decoder-failure retry and the history are the
 * player screen's own.
 *
 * Main thread only (it owns an ExoPlayer). Failures never throw: they become [PlayerUiState.error].
 */
@OptIn(UnstableApi::class)
class PlayerController(
    private val context: Context,
    private val videoSource: VideoSource,
    private val mediaSources: YouTubeMediaSourceFactory,
    private val settings: () -> AppSettings,
    private val sponsorBlock: SponsorBlockRepository,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    /** Keeps the captions the viewer chose ([selectSubtitles]) for the next videos (the settings' [AppSettings.subtitleLanguage]). */
    private val saveSubtitleLanguage: (String?) -> Unit = {},
) : ShortsPlayer {
    private var created = false

    val player: ExoPlayer by lazy(LazyThreadSafetyMode.NONE) { createPlayer().also { created = true } }

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** What the player is playing for now; [PlaybackMode.VIDEO] when nothing plays. */
    var mode = PlaybackMode.VIDEO
        private set

    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var noticeJob: Job? = null
    private var skip: SkipController? = null
    private val skipTarget: SkipTarget by lazy(LazyThreadSafetyMode.NONE) { ExoSkipTarget(player) { _state.value.video?.durationSeconds?.times(1000) } }

    /** What the viewer tapped, by video id: the player screen shows its title and picture while the streams are found. */
    private val previews = LinkedHashMap<String, VideoItem>()

    /** Remembers [video] as the preview of its id for the next [play] (a screen calls this before it navigates). */
    fun remember(video: VideoItem) {
        previews[video.videoId] = video
        while (previews.size > MAX_PREVIEWS) previews.remove(previews.keys.first())
    }

    /**
     * A stream that stops working while it plays (its addresses expired, the connection broke, the download stalled) is extracted
     * again and goes on from the same second, a few times ([StreamRecovery]).
     */
    private val recovery = StreamRecovery(elapsedClock)

    /** How long the player has been waiting for data while it should play (the ticker's steps): a stall at [StreamRecovery.STALL_MS]. */
    private var stalledMs = 0L

    /**
     * This phone's decoder failing on a video is answered once with another try of the same video, with the safe plan
     * (H.264, small pictures); the second failure is the error. One for each video.
     */
    private var decoderRetry = DecoderRetry()

    /** The captions' language the current media source was built with ([YouTubeMediaSourceFactory.create] merges one); null: none. */
    private var sourceSubtitle: String? = null

    /** The default quality applies once per video, when its tracks are known. */
    private var defaultQualityApplied = false
    private var historyRecorded = false
    private var backgroundDisabledVideo = false

    /** How long the Short on screen has played (in the ticker's steps): it enters the history once this reaches [SHORT_WATCHED_MS]. */
    private var shortPlayedMs = 0L

    /** A Short was playing when the app was left (it paused): it plays on when the viewer comes back. */
    private var resumeShortOnStart = false

    /** The streams of the Short after the current one, being looked up ahead ([prefetch]). */
    private var prefetched: Prefetched? = null

    private class Prefetched(val videoId: String, val atMs: Long, val details: Deferred<StreamDetails>)

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus= */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()
        .also {
            // Exact seeks: a skipped sponsor segment and a ±10 s jump land where they say, not on the keyframe before.
            it.setSeekParameters(SeekParameters.EXACT)
            it.addListener(listener)
        }

    // --- starting and stopping ------------------------------------------------------------------------------------

    /**
     * Plays [videoId]. [preview] is what the screen shows until the extraction answers (the item the viewer tapped).
     * Asking for the video that is already loaded does nothing (a rotation or a recomposition must not restart it).
     * [mode]: what it is played for; a [PlaybackMode.SHORT] starts at [startMs] (the start when null), never at a resume point.
     */
    fun play(videoId: String, preview: VideoItem? = null, startMs: Long? = null, mode: PlaybackMode = PlaybackMode.VIDEO) {
        val current = _state.value
        val same = current.video?.videoId == videoId && !current.isOffline && this.mode == mode
        if (same && current.phase != PlayerPhase.IDLE && current.phase != PlayerPhase.ERROR) return
        val start = if (mode == PlaybackMode.SHORT) startMs ?: 0L else startMs
        startLoad(videoId, preview ?: previews[videoId] ?: current.video?.takeIf { same }, start, isReload = false, newMode = mode)
    }

    override fun playShort(video: VideoItem) = play(video.videoId, preview = video, startMs = 0, mode = PlaybackMode.SHORT)

    /**
     * Looks up [videoId]'s streams now and keeps the answer for the next [play] of it, which then starts without asking again:
     * the Shorts viewer asks for the Short after the one on screen while that one plays. One lookup is kept, the newest; a
     * failure is not kept (the play asks again and reports what it finds); an answer older than [PREFETCH_MAX_AGE_MS] is not used.
     */
    override fun prefetch(videoId: String) {
        if (prefetched?.videoId == videoId) return
        prefetched?.details?.cancel()
        prefetched = Prefetched(videoId, elapsedClock(), scope.async { videoSource.stream(videoId, allowLowQuality = true) })
    }

    /** [videoId]'s streams: the lookup [prefetch] made when it is still good, else a new one. */
    private suspend fun streamDetails(videoId: String, reload: Boolean): StreamDetails {
        val ready = prefetched?.takeIf { it.videoId == videoId }
        if (ready != null) {
            prefetched = null
            // A reload (the streams' addresses expired, or the decoder failed on this plan) must not be answered by the old lookup.
            if (!reload && elapsedClock() - ready.atMs <= PREFETCH_MAX_AGE_MS) {
                try {
                    return ready.details.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // our own cancellation ends here; a dropped lookup is just asked again
                } catch (e: Exception) {
                    // The plain lookup below answers, and reports the failure when it is real.
                }
            } else {
                ready.details.cancel()
            }
        }
        return videoSource.stream(videoId, allowLowQuality = true)
    }

    /** Tries the current video again (the error screen's button), from where it was. */
    override fun retry() {
        val video = _state.value.video ?: return
        val position = if (created) player.currentPosition.takeIf { it > 0 } else null
        startLoad(video.videoId, video, position, isReload = false)
    }

    /** [afterMs]: wait this long before asking for the streams (a recovery's pause, while the screen already says it is loading). */
    private fun startLoad(
        videoId: String,
        preview: VideoItem?,
        startMs: Long?,
        isReload: Boolean,
        newMode: PlaybackMode = mode,
        afterMs: Long = 0L,
    ) {
        loadJob?.cancel()
        stopTickers()
        // Under the mode of what was playing: a video leaves its resume point behind, a Short does not.
        saveProgressNow()
        mode = newMode
        if (created) {
            player.stop()
            player.clearMediaItems()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        sourceSubtitle = null
        if (!isReload) {
            recovery.reset()
            // The same video again («Try again») keeps what its decoder failure taught: it plays the safe plan, and does not
            // retry by itself a second time. Another video starts afresh.
            if (_state.value.video?.videoId != videoId) decoderRetry = DecoderRetry()
        }
        defaultQualityApplied = false
        historyRecorded = false
        backgroundDisabledVideo = false
        shortPlayedMs = 0
        stalledMs = 0
        resumeShortOnStart = false
        val item = preview ?: placeholder(videoId)
        _state.value = PlayerUiState(phase = PlayerPhase.LOADING, video = item, speed = _state.value.speed)
        // A Short is not played on in the background (the viewer leaves the app and it waits): no media notification for it.
        if (mode == PlaybackMode.VIDEO) PlaybackService.start(context)
        val newSkip = SkipController(undoAllowed = true, clock = elapsedClock)
        skip = newSkip
        noticeJob?.cancel()
        noticeJob = scope.launch { newSkip.notice.collect { notice -> _state.update { it.copy(skipNotice = notice) } } }

        loadJob = scope.launch {
            // SponsorBlock's data is for videos: a Short is not asked about (one request less for every swipe).
            val segments: Deferred<List<SponsorSegment>>? =
                if (mode == PlaybackMode.VIDEO && settings().sponsorBlockEnabled) async { sponsorBlock.segments(videoId) } else null
            try {
                if (afterMs > 0) delay(afterMs)
                val details = streamDetails(videoId, reload = isReload)
                // The captions the viewer chose last, when this video has them in that language (a Short has none).
                val captions = if (mode == PlaybackMode.VIDEO && !details.video.isLive) details.subtitles.perLanguage() else emptyList()
                val subtitle = captions.matching(settings().subtitleLanguage)?.languageCode
                val source = mediaSources.create(details, maxHeight = 0, subtitleLanguage = subtitle, safe = decoderRetry.safe)
                sourceSubtitle = subtitle
                val video = details.video.fillFrom(item)
                withArtwork(source, video)
                val resume = startMs ?: if (mode == PlaybackMode.SHORT) null else library.resumePosition(videoId)
                prepareFor(mode)
                player.setMediaSource(source, resume ?: C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
                _state.update {
                    it.copy(
                        playWhenReady = true,
                        video = video,
                        details = details,
                        subtitles = captions,
                        subtitleLanguage = subtitle,
                        description = details.description,
                        lowQualityHeight = details.lowQualityHeight,
                        isLive = video.isLive,
                    )
                }
                startTicker()
                if (segments != null) launch { applySegments(segments.await()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /**
     * Plays a downloaded file. Where the viewer stopped is remembered by the video's id, like a streamed one; SponsorBlock
     * skips work offline from its cache.
     */
    fun playOffline(download: DownloadEntity) {
        val uri = download.uri ?: return
        val current = _state.value
        if (current.isOffline && current.video?.videoId == download.videoId && current.phase == PlayerPhase.READY) return
        loadJob?.cancel()
        stopTickers()
        saveProgressNow()
        recovery.reset()
        stalledMs = 0
        defaultQualityApplied = true // a file has one quality
        historyRecorded = false
        backgroundDisabledVideo = false
        mode = PlaybackMode.VIDEO
        val video = VideoItem(
            videoId = download.videoId, title = download.title, channelId = null, channelName = download.channelName,
            durationSeconds = download.durationSeconds, publishedAtEpochMs = null, thumbnailUrl = download.thumbnailUrl,
            viewCount = null, isLive = false, isShortForm = false, url = watchUrl(download.videoId),
        )
        _state.value = PlayerUiState(
            phase = PlayerPhase.LOADING, video = video, isOffline = true, isAudioOnly = download.kind == DownloadKind.AUDIO,
            speed = _state.value.speed,
        )
        PlaybackService.start(context)
        val newSkip = SkipController(undoAllowed = true, clock = elapsedClock)
        skip = newSkip
        noticeJob?.cancel()
        noticeJob = scope.launch { newSkip.notice.collect { notice -> _state.update { it.copy(skipNotice = notice) } } }
        loadJob = scope.launch {
            val segments: Deferred<List<SponsorSegment>>? =
                if (settings().sponsorBlockEnabled) async { sponsorBlock.segments(download.videoId) } else null
            try {
                val metadata = MediaMetadata.Builder().setTitle(download.title).setArtist(download.channelName)
                    .apply { download.thumbnailUrl?.let { setArtworkUri(it.toUri()) } }.build()
                val item = MediaItem.Builder().setMediaId(download.videoId).setUri(uri.toUri()).setMediaMetadata(metadata).build()
                val resume = library.resumePosition(download.videoId)
                prepareFor(PlaybackMode.VIDEO)
                player.setMediaItem(item, resume ?: C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
                _state.update { it.copy(playWhenReady = true) }
                startTicker()
                if (segments != null) launch { applySegments(segments.await()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Stops and forgets everything (the player screen, or the Shorts viewer, was left). */
    override fun stop() {
        loadJob?.cancel()
        stopTickers()
        noticeJob?.cancel()
        saveProgressNow()
        prefetched?.details?.cancel()
        prefetched = null
        skip = null
        if (created) {
            player.stop()
            player.clearMediaItems()
            player.repeatMode = Player.REPEAT_MODE_OFF
        }
        mode = PlaybackMode.VIDEO
        resumeShortOnStart = false
        _state.update { PlayerUiState(speed = it.speed) }
        PlaybackService.stop(context)
    }

    private fun fail(error: Throwable) {
        if (created) player.stop()
        _state.update { it.copy(phase = PlayerPhase.ERROR, error = UiError.of(error), isPlaying = false, playWhenReady = false, isBuffering = false) }
    }

    // --- the viewer's controls ------------------------------------------------------------------------------------

    override fun togglePlay() {
        val p = player
        when {
            p.playbackState == Player.STATE_ENDED -> {
                skip?.onReplay()
                p.seekTo(0)
                p.play()
            }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    /** Plays on (from the start when the video had ended). */
    override fun resumePlayback() {
        val p = player
        if (p.playbackState == Player.STATE_ENDED) {
            skip?.onReplay()
            p.seekTo(0)
        }
        p.play()
    }

    override fun pause() {
        if (created) player.pause()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration
        val target = if (duration != C.TIME_UNSET) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(target)
    }

    /** ±10 s from the double tap (negative: back). */
    fun seekBy(deltaMs: Long) {
        val p = player
        seekTo(p.currentPosition + deltaMs)
        if (p.playbackState == Player.STATE_ENDED && deltaMs < 0) p.play()
    }

    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(PlaybackSpeeds.MIN, PlaybackSpeeds.MAX)
        player.playbackParameters = PlaybackParameters(value)
        _state.update { it.copy(speed = value) }
    }

    /** Pins the picture at [height] (one of [PlayerUiState.qualities]), or null for automatic. */
    fun selectQuality(height: Int?) {
        val p = player
        val params = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height != null) {
            val option = qualitiesOf(p.currentTracks).firstOrNull { it.height == height } ?: return
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            params.addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = params.build()
        _state.update { it.copy(pinnedQuality = height) }
    }

    /**
     * Shows the captions in [languageCode] (one of [PlayerUiState.subtitles]), or none for null, and keeps the choice for the next
     * videos. The media source holds one language: another one is a new source from the same extraction (nothing is asked of
     * YouTube but the captions' file), taken up at the same second.
     */
    fun selectSubtitles(languageCode: String?) {
        val state = _state.value
        saveSubtitleLanguage(languageCode)
        val details = state.details ?: return
        val code = state.subtitles.matching(languageCode)?.languageCode
        _state.update { it.copy(subtitleLanguage = code, cueText = null) }
        when {
            code == null -> setCaptionsShown(false)
            code == sourceSubtitle -> setCaptionsShown(true)
            else -> try {
                val source = mediaSources.create(details, maxHeight = 0, subtitleLanguage = code, safe = decoderRetry.safe)
                withArtwork(source, state.video ?: details.video)
                val position = player.currentPosition
                val playWhenReady = player.playWhenReady
                sourceSubtitle = code
                // The new source's tracks take the quality the viewer pinned (or the default one) again.
                defaultQualityApplied = false
                player.setMediaSource(source, position)
                player.prepare()
                player.playWhenReady = playWhenReady
                setCaptionsShown(true)
            } catch (e: SourceException) {
                // This extraction cannot be planned again (it played, so this is rare): the captions stay as they were.
                _state.update { it.copy(subtitleLanguage = sourceSubtitle) }
            }
        }
    }

    private fun setCaptionsShown(shown: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !shown).build()
    }

    /** «Undo» on the SponsorBlock notice: back to where the skipped part began. */
    fun undoSkip() {
        skip?.undo(skipTarget)
    }

    fun dismissSkipNotice() {
        skip?.dismiss()
    }

    // --- the app going to and from the background -----------------------------------------------------------------

    /**
     * The screen is no longer visible. Playing on as audio is the default (Settings); without it the video pauses.
     * While the sound plays on, the picture is switched off so it does not cost data and battery.
     */
    fun onAppStopped() {
        if (!created || _state.value.phase == PlayerPhase.IDLE) return
        val p = player
        if (mode == PlaybackMode.SHORT) {
            // A Short does not play on as audio: it waits where it is, and plays again when the viewer comes back to it.
            resumeShortOnStart = p.playWhenReady
            p.pause()
            return
        }
        if (!settings().backgroundPlayback) {
            p.pause()
            return
        }
        if (p.isPlaying && !_state.value.isAudioOnly) {
            backgroundDisabledVideo = true
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        }
    }

    /** The screen is visible again: the picture comes back (and a Short that was playing plays on). */
    fun onAppStarted() {
        if (!created) return
        if (resumeShortOnStart) {
            resumeShortOnStart = false
            player.play()
        }
        if (!backgroundDisabledVideo) return
        backgroundDisabledVideo = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }

    // --- internals ------------------------------------------------------------------------------------------------

    /** A playback failure as the player reports it (tests; Robolectric cannot play a stream to the point of failing). */
    internal fun onPlaybackError(error: PlaybackException) = listener.onPlayerError(error)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { if (it.phase == PlayerPhase.IDLE) it else it.copy(isBuffering = true) }
                Player.STATE_READY -> {
                    _state.update { if (it.phase == PlayerPhase.LOADING || it.phase == PlayerPhase.ENDED || it.phase == PlayerPhase.READY) it.copy(phase = PlayerPhase.READY, isBuffering = false) else it }
                    // A Short is entered once it was watched a little ([startTicker]): flicking past it is not watching it.
                    if (mode == PlaybackMode.VIDEO) recordHistoryOnce()
                }
                Player.STATE_ENDED -> {
                    _state.update { it.copy(phase = PlayerPhase.ENDED, isBuffering = false, isPlaying = false) }
                    saveProgressNow()
                }
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { if (it.phase == PlayerPhase.IDLE) it else it.copy(playWhenReady = playWhenReady) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveProgressNow()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val qualities = qualitiesOf(tracks)
            _state.update { it.copy(qualities = qualities) }
            if (!defaultQualityApplied && qualities.isNotEmpty()) {
                defaultQualityApplied = true
                // A quality the viewer pinned on this video stays pinned when its source is rebuilt (other captions).
                val preferred = _state.value.pinnedQuality?.takeIf { pinned -> qualities.any { it.height == pinned } }
                    ?: defaultQualityFor(qualities.map { it.height }, settings().defaultQuality)
                if (preferred != null) selectQuality(preferred)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            _state.update { it.copy(cueText = text.ifEmpty { null }) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            _state.update { it.copy(playingHeight = minOf(videoSize.width, videoSize.height), aspectRatio = ratio) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            val classified = mediaSources.classifyPlaybackError(error)
            val state = _state.value
            if (classified.kind == SourceErrorKind.DEVICE_DECODER && !state.isOffline) {
                // The picture this phone's decoder failed on is left out of every plan from now on. A bonus: the retry below
                // does not depend on it, and nothing here may swallow the error.
                try {
                    mediaSources.recordDecoderFailure(error, player.videoFormat)
                } catch (_: RuntimeException) {
                    // Not remembered; the next plan may offer the picture again.
                }
            }
            if (!state.isOffline && state.video != null && recover(classified.kind)) return
            if (!state.isOffline && state.video != null && decoderRetry.retryAfter(classified)) {
                // The decoder failed on the picture: the same video starts again, safely, from the same second.
                startLoad(state.video.videoId, state.video, player.currentPosition.takeIf { it > 0 }, isReload = true)
                return
            }
            fail(if (state.isOffline) error else classified)
        }
    }

    /**
     * A stream that failed with [kind] while it played is extracted again and goes on from the same second, when [recovery] says
     * so: the stream's addresses expire after about six hours (403), and a connection breaks (the phone changed networks, or
     * slept with the screen off). True when the reload is under way.
     */
    private fun recover(kind: SourceErrorKind): Boolean {
        val video = _state.value.video ?: return false
        val wait = recovery.next(kind) ?: return false
        startLoad(video.videoId, video, player.currentPosition.takeIf { it > 0 }, isReload = true, afterMs = wait)
        return true
    }

    /**
     * Called by the ticker: a video (not a Short, a file or a live stream) that has waited for data for [StreamRecovery.STALL_MS]
     * while it should play has a stalled stream, which never fails by itself; it is recovered like a broken connection.
     */
    private fun checkStall() {
        val state = _state.value
        val waiting = mode == PlaybackMode.VIDEO && !state.isOffline && !state.isLive && state.phase == PlayerPhase.READY &&
            player.playWhenReady && player.playbackState == Player.STATE_BUFFERING
        stalledMs = if (waiting) stalledMs + SkipController.TICK_MS else 0
        if (stalledMs >= StreamRecovery.STALL_MS) {
            stalledMs = 0
            recover(SourceErrorKind.NETWORK)
        }
    }

    /**
     * What differs on the player itself for [playMode], set before each media item: a Short loops, and plays at the normal speed
     * (the Shorts viewer has no speed control, so a speed chosen for a long video must not follow the viewer into it).
     */
    private fun prepareFor(playMode: PlaybackMode) {
        player.repeatMode = if (playMode == PlaybackMode.SHORT) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (playMode == PlaybackMode.SHORT && _state.value.speed != 1f) setSpeed(1f)
    }

    private fun recordHistoryOnce() {
        if (historyRecorded) return
        val video = _state.value.video ?: return
        historyRecorded = true
        scope.launch { library.recordWatched(video) }
    }

    private fun applySegments(all: List<SponsorSegment>) {
        val chosen = all.filter { it.category in settings().sponsorCategories }
        skip?.setSegments(chosen)
        _state.update { it.copy(segments = chosen) }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var sinceSave = 0L
            while (isActive) {
                delay(SkipController.TICK_MS)
                skip?.tick(skipTarget)
                checkStall()
                if (mode == PlaybackMode.SHORT && !historyRecorded && player.isPlaying) {
                    shortPlayedMs += SkipController.TICK_MS
                    if (shortPlayedMs >= SHORT_WATCHED_MS) recordHistoryOnce()
                }
                sinceSave += SkipController.TICK_MS
                if (sinceSave >= SAVE_EVERY_MS) {
                    sinceSave = 0
                    if (player.isPlaying) saveProgressNow()
                }
            }
        }
    }

    private fun stopTickers() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Where the viewer is, for the next time (and the bar under the thumbnail). */
    private fun saveProgressNow() {
        // A Short loops and starts from its start: it has no resume point (and no bar under its picture).
        if (!created || mode == PlaybackMode.SHORT) return
        val state = _state.value
        val video = state.video ?: return
        if (state.phase != PlayerPhase.READY && state.phase != PlayerPhase.ENDED) return
        if (state.isLive) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (state.phase == PlayerPhase.ENDED) duration else player.currentPosition
        scope.launch { library.saveProgress(video.videoId, position, duration) }
    }

    /** The thumbnail goes to the media notification and the lock screen when the source can take it. */
    private fun withArtwork(source: MediaSource, video: VideoItem) {
        val artwork = video.thumbnailUrl ?: "https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg"
        val current = source.mediaItem
        val updated = current.buildUpon()
            .setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkUri(artwork.toUri()).build())
            .build()
        if (source.canUpdateMediaItem(updated)) source.updateMediaItem(updated)
    }

    private fun placeholder(videoId: String) = VideoItem(
        videoId = videoId, title = "", channelId = null, channelName = null, durationSeconds = null, publishedAtEpochMs = null,
        thumbnailUrl = null, viewCount = null, isLive = false, isShortForm = false, url = watchUrl(videoId),
    )

    /** The extraction's details, with what the tapped item knew filling any blanks. */
    private fun VideoItem.fillFrom(preview: VideoItem) = copy(
        title = title.ifBlank { preview.title },
        channelName = channelName ?: preview.channelName,
        thumbnailUrl = thumbnailUrl ?: preview.thumbnailUrl,
        viewCount = viewCount ?: preview.viewCount,
        publishedAtEpochMs = publishedAtEpochMs ?: preview.publishedAtEpochMs,
        durationSeconds = durationSeconds ?: preview.durationSeconds,
    )

    companion object {
        const val SEEK_STEP_MS = 10_000L
        private const val SAVE_EVERY_MS = 5_000L
        private const val MAX_PREVIEWS = 40

        /** A Short enters the history after playing this long. */
        const val SHORT_WATCHED_MS = 3_000L

        /** A lookup made ahead is used for this long: the Short after the current one is a few seconds away, not minutes. */
        const val PREFETCH_MAX_AGE_MS = 5 * 60_000L

        /** One option per picture height from the player's video tracks, best first. */
        fun qualitiesOf(tracks: Tracks): List<QualityOption> {
            val all = ArrayList<QualityOption>()
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    val height = qualityHeight(format)
                    if (height <= 0) continue
                    val fps = format.frameRate.takeIf { it > 0f }?.roundToInt() ?: 0
                    all += QualityOption(height, fps, groupIndex, trackIndex, format.bitrate.coerceAtLeast(0), codecRank(format.codecs))
                }
            }
            return all.groupBy { it.height }
                .map { (_, sameHeight) -> sameHeight.minWith(compareBy<QualityOption> { it.codecRank }.thenByDescending { it.fps }.thenByDescending { it.bitrate }) }
                .sortedByDescending { it.height }
        }

        /** YouTube's label is the short side: a vertical 1080x1920 video is "1080p". */
        fun qualityHeight(format: Format): Int =
            if (format.width > 0 && format.height > 0) minOf(format.width, format.height) else format.height.coerceAtLeast(0)

        private fun codecRank(codecs: String?): Int = when {
            codecs == null -> 3
            codecs.startsWith("avc") -> 0
            codecs.startsWith("vp09") || codecs.startsWith("vp9") -> 1
            codecs.startsWith("av01") -> 2
            else -> 3
        }

        /**
         * The height a default quality setting picks among [available] (any order): the best one up to [cap], or the
         * lowest there is when nothing is that low. Null for automatic ([cap] ≤ 0) and for no choice at all.
         */
        fun defaultQualityFor(available: List<Int>, cap: Int): Int? {
            if (cap <= 0 || available.isEmpty()) return null
            return available.filter { it <= cap }.maxOrNull() ?: available.min()
        }
    }
}

/** The ExoPlayer as the SponsorBlock controller sees it. */
@OptIn(UnstableApi::class)
internal class ExoSkipTarget(private val player: ExoPlayer, private val fallbackDurationMs: () -> Long?) : SkipTarget {
    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)

    override val durationMs: Long?
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: fallbackDurationMs()?.takeIf { it > 0 }

    override val isPlaying: Boolean get() = player.isPlaying
    override val isLive: Boolean get() = player.isCurrentMediaItemLive
    override val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED

    override fun seekExactly(positionMs: Long, automatic: Boolean) {
        player.seekTo(positionMs)
    }
}

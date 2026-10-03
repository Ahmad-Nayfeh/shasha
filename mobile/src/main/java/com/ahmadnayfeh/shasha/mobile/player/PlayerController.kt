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
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
) {
    private var created = false

    val player: ExoPlayer by lazy(LazyThreadSafetyMode.NONE) { createPlayer().also { created = true } }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

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

    /** A 403 from googlevideo after the URLs expired is cured once by extracting again. */
    private var expiryRetried = false

    /** The default quality applies once per video, when its tracks are known. */
    private var defaultQualityApplied = false
    private var historyRecorded = false
    private var backgroundDisabledVideo = false

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
     */
    fun play(videoId: String, preview: VideoItem? = null, startMs: Long? = null) {
        val current = _state.value
        val same = current.video?.videoId == videoId && !current.isOffline
        if (same && current.phase != PlayerPhase.IDLE && current.phase != PlayerPhase.ERROR) return
        startLoad(videoId, preview ?: previews[videoId] ?: current.video?.takeIf { same }, startMs, isReload = false)
    }

    /** Tries the current video again (the error screen's button), from where it was. */
    fun retry() {
        val video = _state.value.video ?: return
        val position = if (created) player.currentPosition.takeIf { it > 0 } else null
        startLoad(video.videoId, video, position, isReload = false)
    }

    private fun startLoad(videoId: String, preview: VideoItem?, startMs: Long?, isReload: Boolean) {
        loadJob?.cancel()
        stopTickers()
        saveProgressNow()
        if (created) {
            player.stop()
            player.clearMediaItems()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        if (!isReload) expiryRetried = false
        defaultQualityApplied = false
        historyRecorded = false
        backgroundDisabledVideo = false
        val item = preview ?: placeholder(videoId)
        _state.value = PlayerUiState(phase = PlayerPhase.LOADING, video = item, speed = _state.value.speed)
        PlaybackService.start(context)
        val newSkip = SkipController(undoAllowed = true, clock = elapsedClock)
        skip = newSkip
        noticeJob?.cancel()
        noticeJob = scope.launch { newSkip.notice.collect { notice -> _state.update { it.copy(skipNotice = notice) } } }

        loadJob = scope.launch {
            val segments: Deferred<List<SponsorSegment>>? =
                if (settings().sponsorBlockEnabled) async { sponsorBlock.segments(videoId) } else null
            try {
                val details = videoSource.stream(videoId, allowLowQuality = true)
                val source = mediaSources.create(details, maxHeight = 0, subtitleLanguage = null)
                val video = details.video.fillFrom(item)
                withArtwork(source, video)
                val resume = startMs ?: library.resumePosition(videoId)
                player.setMediaSource(source, resume ?: C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
                _state.update {
                    it.copy(
                        video = video,
                        details = details,
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
        expiryRetried = false
        defaultQualityApplied = true // a file has one quality
        historyRecorded = false
        backgroundDisabledVideo = false
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
                player.setMediaItem(item, resume ?: C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
                startTicker()
                if (segments != null) launch { applySegments(segments.await()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Stops and forgets everything (the player screen was left). */
    fun stop() {
        loadJob?.cancel()
        stopTickers()
        noticeJob?.cancel()
        saveProgressNow()
        skip = null
        if (created) {
            player.stop()
            player.clearMediaItems()
        }
        _state.update { PlayerUiState(speed = it.speed) }
        PlaybackService.stop(context)
    }

    private fun fail(error: Throwable) {
        if (created) player.stop()
        _state.update { it.copy(phase = PlayerPhase.ERROR, error = UiError.of(error), isPlaying = false, isBuffering = false) }
    }

    // --- the viewer's controls ------------------------------------------------------------------------------------

    fun togglePlay() {
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
    fun resumePlayback() {
        val p = player
        if (p.playbackState == Player.STATE_ENDED) {
            skip?.onReplay()
            p.seekTo(0)
        }
        p.play()
    }

    fun pause() {
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
        if (!settings().backgroundPlayback) {
            p.pause()
            return
        }
        if (p.isPlaying && !_state.value.isAudioOnly) {
            backgroundDisabledVideo = true
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        }
    }

    /** The screen is visible again: the picture comes back. */
    fun onAppStarted() {
        if (!created || !backgroundDisabledVideo) return
        backgroundDisabledVideo = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }

    // --- internals ------------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { if (it.phase == PlayerPhase.IDLE) it else it.copy(isBuffering = true) }
                Player.STATE_READY -> {
                    _state.update { if (it.phase == PlayerPhase.LOADING || it.phase == PlayerPhase.ENDED || it.phase == PlayerPhase.READY) it.copy(phase = PlayerPhase.READY, isBuffering = false) else it }
                    recordHistoryOnce()
                }
                Player.STATE_ENDED -> {
                    _state.update { it.copy(phase = PlayerPhase.ENDED, isBuffering = false, isPlaying = false) }
                    saveProgressNow()
                }
                else -> Unit
            }
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
                val preferred = defaultQualityFor(qualities.map { it.height }, settings().defaultQuality)
                if (preferred != null) selectQuality(preferred)
            }
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
            if (classified.kind == SourceErrorKind.BLOCKED_403 && !expiryRetried && !state.isOffline && state.video != null) {
                // The stream's URLs expire after about six hours: extract again and go on from the same second.
                expiryRetried = true
                startLoad(state.video.videoId, state.video, player.currentPosition.takeIf { it > 0 }, isReload = true)
                return
            }
            fail(if (state.isOffline) error else classified)
        }
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
        if (!created) return
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

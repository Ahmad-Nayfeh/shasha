package com.ahmadnayfeh.shasha.ui.screens.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.PlayedMeter
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.player.StreamFreshness
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.PlayCheck
import com.ahmadnayfeh.shasha.remote.ConfirmAnswer
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What follows the end of the video. */
sealed interface PlaybackEnd {
    /** A single video: a calm end card, nothing plays by itself. */
    data object Finished : PlaybackEnd

    /** Inside a playlist: [next] (at [index] of the playlist) plays after a short countdown. */
    data class UpNext(val next: VideoItem, val index: Int = -1) : PlaybackEnd

    /** The last video of a playlist. */
    data object PlaylistFinished : PlaybackEnd
}

/** The next playlist video that may play here, and its position in the playlist. */
data class NextVideo(val video: VideoItem, val index: Int)

/**
 * A failure that means the video cannot play here (SPEC §4 lock: it ends, [PlaybackLocking.onUnavailable]):
 * whatever YouTube answered. Not [SourceErrorKind.NETWORK]: no network is the TV's side, it passes, and a
 * child can cause it; the lock stays and the video is tried again.
 */
internal val SourceErrorKind.endsTheLock: Boolean get() = this != SourceErrorKind.NETWORK

/** Refusals that leave the lock as it is: the session's screens pause the video; "locked" is another video's lock. */
private val KEEPS_THE_LOCK = setOf(DenyReason.TIME_UP, DenyReason.OUTSIDE_SCHEDULE, DenyReason.LOCKED)

/** Asks the policy whether this video may start now ([com.ahmadnayfeh.shasha.policy.PolicyGate.checkPlay]). */
fun interface PlayGate {
    /** @throws SourceException when the check had to extract the video and that failed. */
    suspend fun check(videoId: String): PlayCheck
}

/**
 * v1.2 (owner decision): a video that would start in Serious Mode while nothing on the TV could end it early (the adult
 * profile with its way out turned off) first waits for the parent's «تأكيد» on the phone
 * ([com.ahmadnayfeh.shasha.remote.PhoneConfirmations]).
 */
interface StartConfirmation {
    /** Whether [videoId] must be confirmed from the phone before it starts now (not when the lock already holds it). */
    fun needed(videoId: String): Boolean

    /** Asks the phone; suspends until it answers or the wait ends (cancelled: the request is withdrawn). */
    suspend fun ask(videoId: String, title: String): ConfirmAnswer
}

/**
 * Loads one video's stream, replaces it when its URLs go stale ([StreamFreshness]), and remembers where
 * playback was when the player was released. With a [session] it also resumes from the saved position,
 * records progress and history, and toggles watch later; [nextInPlaylist] (null outside a playlist)
 * finds the video that plays next.
 *
 * [gate] is asked before anything plays (SPEC §3): before the first extraction, before every fresh one,
 * and before a released player starts again (back from Home, the "time up" screen, a lock): the rules
 * may have changed meanwhile (an allowlist edited from the phone). A refusal is [Load.Denied]: no player.
 *
 * [lock] (SPEC §4) hears how the video goes: every start (not after the end, until a replay), the end
 * with the playlist video that follows ([playlistId]) and how much of the video was really played
 * ([PlayedMeter]: skipped to its end, it stays locked; what SponsorBlock skipped is not held against it), and
 * a video that cannot play here any more (not for a lost network: the lock stays and the video is tried
 * again, [retry]).
 *
 * [startAtMs]: where a shared link asks the video to start (`t=`); it replaces the saved position (past the
 * video's end, the video starts from the beginning).
 *
 * [allowLowQuality] (owner decision, a child profile): a video YouTube only offers as a muxed file of 360p or
 * less plays that file (the player shows a "360p" badge) instead of the error screen. Otherwise that is
 * [SourceErrorKind.LOW_QUALITY_ONLY], and the adult may still choose it for this visit ([playLowQuality]).
 *
 * [suggestionsFor] («الفيديوهات المقترحة», null: none) is asked once the video is extracted, so it answers from that
 * same extraction (VideoSource.related): the player's «مقترحة» row and its end card. It applies the profile's rules.
 */
class PlayerViewModel(
    private val videoId: String,
    private val streams: StreamRepository,
    private val session: WatchSession? = null,
    private val nextInPlaylist: (suspend () -> NextVideo?)? = null,
    private val gate: PlayGate? = null,
    /** v1.2: the phone's «تأكيد» before a start only the phone could end early (null: never asked, e.g. تشخيص's videos). */
    private val startConfirmation: StartConfirmation? = null,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val lock: PlaybackLocking? = null,
    private val playlistId: String? = null,
    startAtMs: Long? = null,
    allowLowQuality: Boolean = false,
    /** The speed a video starts at (Settings → التشغيل, 1× by default). */
    defaultSpeed: Float = 1f,
    private val suggestionsFor: (suspend (videoId: String) -> List<VideoItem>)? = null,
) : ViewModel() {

    sealed interface Load {
        data object Loading : Load

        /**
         * [freshness] tells when [stream]'s URLs must be replaced by a fresh extraction. Compared by identity:
         * the same stream allowed again (after a new check) is a new start.
         */
        class Ready(val stream: StreamDetails, val freshness: StreamFreshness) : Load
        data class Failed(val error: SourceException) : Load

        /** The profile's rules say no ([reason]): nothing is played. */
        data class Denied(val reason: DenyReason) : Load

        /** v1.2: allowed, but waiting for the parent's «تأكيد» on the phone before [title] starts ([StartConfirmation]). */
        data class AwaitingPhone(val title: String) : Load

        /** v1.2: the phone said «رفض» or did not answer in time ([answer]): the video does not play. */
        data class NotConfirmed(val answer: ConfirmAnswer) : Load
    }

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()

    /** Survives the player being released while the app is in the background, and re-extractions. */
    var resume: ResumeState = ResumeState(speed = defaultSpeed)

    private val _resumedFromMs = MutableStateFlow<Long?>(null)

    /** Set when playback continued from a saved position ("استُكمل من 12:34"). */
    val resumedFromMs: StateFlow<Long?> = _resumedFromMs.asStateFlow()

    /** Null while unknown or without a session (the OSD then shows no watch-later chip). */
    val savedForLater: StateFlow<Boolean?> = (session?.isSavedForLater() ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _end = MutableStateFlow<PlaybackEnd?>(null)
    val end: StateFlow<PlaybackEnd?> = _end.asStateFlow()

    /**
     * The video reached its end by a jump, not by playing ([com.ahmadnayfeh.shasha.policy.PlayedPart.endedByItself]):
     * its lock stays, so the end card does not send a child home by itself.
     */
    private val _skippedToEnd = MutableStateFlow(false)
    val skippedToEnd: StateFlow<Boolean> = _skippedToEnd.asStateFlow()

    private val _suggestions = MutableStateFlow<List<VideoItem>>(emptyList())

    /** «مقترحة»: what may be suggested beside this video (empty until it is extracted, or when there are none). */
    val suggestions: StateFlow<List<VideoItem>> = _suggestions.asStateFlow()
    private var suggestionsAsked = false

    /** What the players of this visit really played (SPEC §4). */
    private val played = PlayedMeter()

    private var extraction: Job? = null
    private var resumeLookedUp = false

    /** The player was released since the last check: ask the policy again before the next one starts. */
    private var recheck = false

    /** A link's start, for the first extraction only (then playback goes on from where it is). */
    private var linkStartMs: Long? = startAtMs?.coerceAtLeast(0)

    /** This visit's start needed no «تأكيد» from the phone, or got it: later extractions (stale URLs, a retry) do not ask again. */
    private var startCleared = false

    /** Whether a low-quality (muxed ≤360p) stream may play: the profile's rule, or the adult's choice ([playLowQuality]). */
    var lowQualityAllowed: Boolean = allowLowQuality
        private set

    init {
        extract(fresh = false)
    }

    /**
     * Asked before a player is created for [ready]. False when [ready] is no longer current, or when its
     * URLs expire soon (e.g. back after hours in the background): a fresh extraction then replaces it,
     * and the new player starts from [resume].
     */
    fun canStart(ready: Load.Ready): Boolean {
        if (_load.value !== ready) return false
        if (recheck && gate != null) {
            recheck = false
            // A video that already ended only shows its end card: nothing plays, so nothing is asked (a
            // child's lock has moved on to the next playlist video meanwhile: asked again, this one would
            // be refused as "locked" and the countdown lost). The next video's own player asks for it.
            if (_end.value == null) {
                checkAgain(ready)
                return false
            }
        }
        if (!ready.freshness.refreshBeforeStart(elapsedClock())) return true
        extract(fresh = true)
        return false
    }

    /**
     * Playback of [ready] failed at [at]. HTTP 403 on URLs that have been in use for a while gets one
     * fresh extraction, continuing at [at]; anything else, or a 403 again soon after, is the error screen.
     */
    fun onPlaybackError(ready: Load.Ready, error: SourceException, at: ResumeState) {
        if (_load.value !== ready) return // a player of an extraction that was already replaced
        resume = at
        if (error.kind == SourceErrorKind.BLOCKED_403 && ready.freshness.refreshAfterRefusal(elapsedClock())) {
            extract(fresh = true)
        } else {
            fail(error)
        }
    }

    /** Show the error screen, and do not reuse this stream's (probably refused) URLs. */
    fun fail(error: SourceException) {
        extraction?.cancel()
        show(Load.Failed(error))
        viewModelScope.launch { streams.invalidate(videoId) }
    }

    /**
     * The player was released (Home, standby, leaving): the next one continues from [at], and the
     * "استُكمل من …" hint, which was about the saved position, is not shown again.
     */
    fun onReleased(at: ResumeState) {
        resume = at
        _resumedFromMs.value = null
        recheck = true
        played.stoppedAt(at.positionMs)
    }

    /**
     * A player started for this video: it goes into the history, and it is locked (SPEC §4). Not a player
     * that comes back on the end card (after Home, say): the video that ended stays as its end left it
     * until a replay.
     */
    fun onPlaybackStarted(video: VideoItem) {
        session?.onStarted(video)
        if (_end.value == null) {
            played.startedAt(resume.positionMs)
            lock?.onStarted(videoId, playlistId)
        }
    }

    /** The player's position jumped (a seek, from anywhere): that part was not played. */
    fun onJumped(fromMs: Long, toMs: Long) {
        played.jumped(fromMs, toMs)
    }

    /**
     * SponsorBlock skipped [fromMs] to [toMs] by itself: nobody chose it, so it is not a skip towards the end
     * either (SPEC §4); it simply is not part of what was left to watch.
     */
    fun onAutoSkipped(fromMs: Long, toMs: Long) {
        played.autoSkipped(fromMs, toMs)
    }

    /** Every 10 s while playing, on pause, and when the player is released. */
    fun saveProgress(progress: PlaybackProgress) {
        session?.save(progress)
    }

    /**
     * The video reached its end: mark it watched and decide what comes next. The lock hears it once that
     * is known, with how much of it was really played: a child's playlist keeps it locked on the next
     * video; a video skipped to its end stays locked; anything else releases it.
     */
    fun onEnded(progress: PlaybackProgress) {
        if (_end.value != null) return
        session?.onEnded(progress)
        val end = if (progress.durationMs > 0) maxOf(progress.positionMs, progress.durationMs) else progress.positionMs
        val part = played.endedAt(end, progress.durationMs, progress.isLive)
        _skippedToEnd.value = !part.endedByItself
        val next = nextInPlaylist
        if (next == null) {
            _end.value = PlaybackEnd.Finished
            lock?.onEnded(videoId, nextVideoId = null, played = part)
            return
        }
        viewModelScope.launch {
            try {
                val ending = next()?.let { PlaybackEnd.UpNext(it.video, it.index) } ?: PlaybackEnd.PlaylistFinished
                _end.value = ending
                lock?.onEnded(videoId, nextVideoId = (ending as? PlaybackEnd.UpNext)?.next?.videoId, played = part)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.asSourceException())
            }
        }
    }

    /**
     * «إعادة التشغيل» is a new start: when it would need the phone's «تأكيد» ([StartConfirmation.needed]: the lock ended with
     * the video) the screen opens the video afresh instead, and the new visit asks for it.
     */
    fun replayNeedsConfirmation(): Boolean = startConfirmation?.needed(videoId) == true

    /** The TV's «إلغاء» while the phone's «تأكيد» is awaited: the request is withdrawn at once (the screen then leaves). */
    fun cancelStart() {
        if (_load.value is Load.AwaitingPhone) extraction?.cancel()
    }

    /** «إعادة التشغيل» from the end card: the video starts again from the beginning, and so does its lock. */
    fun onReplay() {
        _end.value = null
        _skippedToEnd.value = false
        played.reset()
        lock?.onStarted(videoId, playlistId)
    }

    /**
     * «إعادة المحاولة» on the error screen, or the network is back: a video that failed is asked for and
     * extracted again (its lock stayed: a lost network does not end it).
     */
    fun retry() {
        if (_load.value is Load.Failed) extract(fresh = true)
    }

    /**
     * «شغّل بجودة 360p على أي حال» on the adult's error screen: the same video again, its muxed ≤360p file allowed
     * for the rest of this visit (the player then shows the "360p" badge). Policy checks are asked as always.
     */
    fun playLowQuality() {
        if ((_load.value as? Load.Failed)?.error?.kind != SourceErrorKind.LOW_QUALITY_ONLY) return
        lowQualityAllowed = true
        extract(fresh = false)
    }

    /** The network came back: a video that failed for the lack of it is tried again by itself. */
    fun retryAfterNetwork() {
        if ((_load.value as? Load.Failed)?.error?.kind == SourceErrorKind.NETWORK) extract(fresh = true)
    }

    fun toggleWatchLater(video: VideoItem) {
        val saved = savedForLater.value ?: return
        session?.setSavedForLater(video, !saved)
    }

    /** A released player is about to start again: the same stream, if the policy still says yes. */
    private fun checkAgain(ready: Load.Ready) {
        val check = gate ?: return
        extraction?.cancel()
        extraction = viewModelScope.launch {
            show(
                try {
                    when (val result = check.check(videoId)) {
                        is PlayCheck.Denied -> Load.Denied(result.reason)
                        is PlayCheck.Allowed -> Load.Ready(ready.stream, ready.freshness)
                    }
                } catch (e: SourceException) {
                    Load.Failed(e)
                },
            )
        }
    }

    /**
     * [next] replaces what is shown. A video that cannot play here any more releases its lock
     * ([endsTheLock]): YouTube no longer serves it, or the rules now say no. Not a lost network (a child
     * can pull a cable, and a TV that restarts often opens the locked video before its Wi-Fi is up): the
     * lock stays and the video is tried again. Nor "time up" and "outside schedule": the session's own
     * screen covers the player then, and the video continues, still locked, once the parent adds time.
     * Nor "locked": that is another lock's answer, never this video's.
     */
    private fun show(next: Load) {
        _load.value = next
        val unavailable = when (next) {
            is Load.Failed -> next.error.kind.endsTheLock
            is Load.Denied -> next.reason !in KEEPS_THE_LOCK
            else -> false
        }
        if (unavailable) lock?.onUnavailable(videoId)
        if (next is Load.Ready) loadSuggestions()
    }

    /**
     * Once per visit, after the first extraction (whose related items VideoSource.related then answers from). They are
     * extra: a failure only leaves the row empty.
     */
    private fun loadSuggestions() {
        val find = suggestionsFor ?: return
        if (suggestionsAsked) return
        suggestionsAsked = true
        viewModelScope.launch {
            _suggestions.value = try {
                find(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * v1.2: true when this start may go on: no «تأكيد» is needed ([StartConfirmation.needed]; once per visit), or the
     * phone gave it. Otherwise [Load.NotConfirmed] shows (the screen leaves) and nothing plays.
     */
    private suspend fun confirmStart(title: String): Boolean {
        val confirmation = startConfirmation
        if (startCleared || confirmation == null || !confirmation.needed(videoId)) {
            startCleared = true
            return true
        }
        _load.value = Load.AwaitingPhone(title)
        val answer = confirmation.ask(videoId, title)
        if (answer == ConfirmAnswer.CONFIRMED) {
            startCleared = true
            return true
        }
        show(Load.NotConfirmed(answer))
        return false
    }

    private fun extract(fresh: Boolean) {
        extraction?.cancel()
        _load.value = Load.Loading
        extraction = viewModelScope.launch {
            if (fresh) streams.invalidate(videoId)
            // The saved position is read while the stream is extracted (only once: later extractions
            // continue from where this visit got to).
            val savedPosition = if (session != null && !resumeLookedUp && linkStartMs == null) {
                async { runCatching { session.resumePositionMs() }.getOrDefault(0L) }
            } else {
                null
            }
            val next = try {
                // The policy first: nothing is prepared for a video that may not play here.
                val stream = when (val check = gate?.check(videoId)) {
                    is PlayCheck.Denied -> {
                        show(Load.Denied(check.reason))
                        return@launch
                    }
                    // The check's own look-up may be low quality: only taken when that may play here.
                    is PlayCheck.Allowed -> check.stream?.takeIf { lowQualityAllowed || it.lowQualityHeight == null }
                        ?: streams.stream(videoId, lowQualityAllowed)
                    null -> streams.stream(videoId, lowQualityAllowed)
                }
                // v1.2: nothing starts that only the phone could end early before the phone confirms it.
                if (!confirmStart(stream.video.title)) return@launch
                savedPosition?.await()?.let { position ->
                    resumeLookedUp = true
                    if (position > 0 && !stream.video.isLive && resume.positionMs == 0L) {
                        resume = resume.copy(positionMs = position)
                        _resumedFromMs.value = position
                    }
                }
                linkStartMs?.let { at ->
                    // The link's start instead of the saved position; a start past the end (a wrong t=) is ignored.
                    linkStartMs = null
                    resumeLookedUp = true
                    val durationMs = stream.video.durationSeconds?.let { it * 1000 }
                    if (at > 0 && !stream.video.isLive && resume.positionMs == 0L && (durationMs == null || at < durationMs)) {
                        resume = resume.copy(positionMs = at)
                    }
                }
                Load.Ready(stream, StreamFreshness(elapsedClock(), stream.playback.expiresAtEpochMs, wallClock()))
            } catch (e: SourceException) {
                Load.Failed(e)
            }
            show(next)
        }
    }
}

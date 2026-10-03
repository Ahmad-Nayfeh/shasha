package com.ahmadnayfeh.shasha.mobile.sponsorblock

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The player as [SkipController] sees it (the real one wraps ExoPlayer; tests use a fake). Main thread. */
interface SkipTarget {
    val positionMs: Long

    /** The video's length; null while nobody knows it. */
    val durationMs: Long?

    /** Actually playing: not paused, not buffering, not ended. */
    val isPlaying: Boolean
    val isLive: Boolean
    val isEnded: Boolean

    /**
     * Jumps to exactly [positionMs] (never to a keyframe before it, which could be inside the segment).
     * [automatic]: SponsorBlock's own skip; false for the viewer's undo, an ordinary jump back.
     */
    fun seekExactly(positionMs: Long, automatic: Boolean)
}

/**
 * «Skipped: …», shown from [shownAtMs] until [untilMs] (the controller's clock). [undoable]: the viewer may take
 * the skip back until then ([SkipController.undo]): playback returns to [fromMs] and those segments play.
 */
@Immutable
data class SkipNotice(
    val id: Long,
    val category: SponsorCategory,
    val undoable: Boolean,
    /** Where the skipped part began: an undo goes back here. */
    val fromMs: Long,
    /** Where playback landed. */
    val toMs: Long,
    val shownAtMs: Long,
    val untilMs: Long,
    val segmentIds: Set<String>,
)

/**
 * The automatic skip, for one viewing of one video. [tick] is called about every [TICK_MS]; while the video plays and
 * the position is inside a segment to skip, it jumps to the segment's end and shows a [notice].
 *
 * - A skipped segment is not skipped again while playback stays past its start: seeking back into it plays it
 *   (and a jump that landed a little short can never loop, [LANDING_SLACK_MS]). Playback seen before its start
 *   again (the viewer went back to watch something earlier) skips it again when it comes. Once the video has
 *   ended, a replay skips everything again ([onReplay]).
 * - [undoAllowed]: the notice offers «Undo» for [UNDO_WINDOW_MS]; [undo] goes back to the segment's start and never
 *   skips it again. Without it the notice is only a message, for [NOTICE_MS].
 * - A jump never reaches the video's end: a segment that runs to the end (an outro, an end screen) is skipped
 *   to [END_GUARD_MS] before it, and that last moment plays, so the video ends by itself, never by a jump.
 * - Nothing is skipped in a live stream, nor a segment shorter than [MIN_SEGMENT_MS], nor by a jump shorter
 *   than [MIN_JUMP_MS] (the last moments of a segment that runs to the end simply play).
 * - Segments that touch or overlap (up to [MERGE_GAP_MS] apart) are one jump and one notice.
 *
 * Main thread only.
 */
class SkipController(
    val undoAllowed: Boolean,
    /** Elapsed milliseconds (a clock that never jumps). */
    private val clock: () -> Long,
    /** How long the viewer may take a skip back ([UNDO_WINDOW_MS] by default). */
    private val undoWindowMs: () -> Long = { UNDO_WINDOW_MS },
    /** How long the notice stays without an undo ([NOTICE_MS] by default). */
    private val noticeMs: () -> Long = { NOTICE_MS },
) {
    private var segments: List<SponsorSegment> = emptyList()

    /** Skipped, and playback has not been before their start since: not again. */
    private val skipped = HashSet<String>()

    /** Taken back by the viewer: they play, now and after a replay. */
    private val suppressed = HashSet<String>()

    /** Where the last skip was aimed: playback just short of it is that jump landing, not the viewer going back. */
    private var lastLanding = NO_LANDING
    private var nextNoticeId = 0L

    private val _notice = MutableStateFlow<SkipNotice?>(null)
    val notice: StateFlow<SkipNotice?> = _notice.asStateFlow()

    /** The segments in force (the selected categories), sorted by start. */
    val current: List<SponsorSegment> get() = segments

    fun setSegments(list: List<SponsorSegment>) {
        segments = list.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    /** One look at [target]: expire the notice, re-arm what playback went back before, and skip when it entered a segment. */
    fun tick(target: SkipTarget) {
        // Most videos have no segments (or none selected): then the player is not even looked at.
        if (segments.isEmpty() && _notice.value == null) return
        val now = clock()
        if (target.isEnded) {
            // This viewing is over: whatever starts it again (the end card's replay, the controls' own button)
            // is a new viewing that skips again.
            skipped.clear()
            _notice.value = null
        }
        _notice.value?.let { if (now >= it.untilMs) _notice.value = null }
        if (target.isLive) return
        val position = target.positionMs
        if (skipped.isNotEmpty() && position !in lastLanding - LANDING_SLACK_MS..lastLanding) {
            segments.forEach { if (position < it.startMs) skipped -= it.id }
        }
        if (!target.isPlaying) return
        val duration = target.durationMs ?: return
        val first = segments.firstOrNull { position in it && isSkippable(it) } ?: return

        val chain = mutableListOf(first)
        var chainEnd = first.endMs
        while (true) {
            val next = segments.firstOrNull {
                it !in chain && isSkippable(it) && it.startMs <= chainEnd + MERGE_GAP_MS && it.endMs > chainEnd
            } ?: break
            chain += next
            chainEnd = next.endMs
        }
        val landing = minOf(chainEnd, duration - END_GUARD_MS)
        // Too close to the end, or to where the chain ends: the rest simply plays.
        if (landing - position < MIN_JUMP_MS) return
        chain.mapTo(skipped) { it.id }
        lastLanding = landing
        target.seekExactly(landing, automatic = true)
        _notice.value = SkipNotice(
            id = ++nextNoticeId,
            category = first.category,
            undoable = undoAllowed,
            fromMs = first.startMs,
            toMs = landing,
            shownAtMs = now,
            untilMs = now + if (undoAllowed) undoWindowMs() else noticeMs(),
            segmentIds = chain.mapTo(LinkedHashSet()) { it.id },
        )
    }

    /**
     * «Undo»: back to where the skipped part began, and it is never skipped again. False (and nothing
     * happens) when undo is not allowed, or once the notice has gone.
     */
    fun undo(target: SkipTarget): Boolean {
        val shown = _notice.value ?: return false
        if (!shown.undoable || clock() >= shown.untilMs) return false
        suppressed += shown.segmentIds
        _notice.value = null
        target.seekExactly(shown.fromMs, automatic = false)
        return true
    }

    /** The notice goes (BACK while it offered the undo): the skip stays. */
    fun dismiss() {
        _notice.value = null
    }

    /** A replay: a new viewing skips again (except what the viewer chose to watch). */
    fun onReplay() {
        skipped.clear()
        lastLanding = NO_LANDING
        _notice.value = null
    }

    private fun isSkippable(segment: SponsorSegment): Boolean =
        segment.action == SegmentAction.SKIP &&
            segment.id !in skipped &&
            segment.id !in suppressed &&
            segment.lengthMs >= MIN_SEGMENT_MS

    companion object {
        /** How often the player is looked at while the video is on screen (a few field reads). */
        const val TICK_MS = 250L

        /** How long «Skipped: …» stays without an undo. */
        const val NOTICE_MS = 3_000L

        /** How long the viewer can take a skip back. */
        const val UNDO_WINDOW_MS = 5_000L

        const val MIN_SEGMENT_MS = 1_000L

        /** A shorter jump is not worth one (and its notice): what is left of the segment plays. */
        const val MIN_JUMP_MS = 500L

        /** A skip lands at least this long before the video's end: the video ends by itself, never by a jump. */
        const val END_GUARD_MS = 1_000L
        const val MERGE_GAP_MS = 1_000L

        /**
         * Up to this much short of a skip's aim, playback is that skip landing (a keyframe, should the exact seek
         * ever snap to one), not the viewer going back: what it skipped is not armed again, so it cannot loop.
         */
        const val LANDING_SLACK_MS = 5_000L

        private const val NO_LANDING = Long.MIN_VALUE / 2
    }
}

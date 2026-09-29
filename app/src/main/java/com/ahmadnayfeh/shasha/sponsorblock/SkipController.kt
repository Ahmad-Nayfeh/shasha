package com.ahmadnayfeh.shasha.sponsorblock

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

    /** Jumps to exactly [positionMs] (never to a keyframe before it, which could be inside the segment). */
    fun seekExactly(positionMs: Long)

    fun setMuted(muted: Boolean)
}

/**
 * «تم تخطي: …», shown from [shownAtMs] until [untilMs] (the controller's clock). [undoable]: the adult may take
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
 * SPEC §1's automatic skip, for one screen visit of one video (it outlives the players of that visit: Home
 * releases a player, a new one continues). [tick] is called about every [TICK_MS]; while the video plays and
 * the position is inside a segment to skip, it jumps to the segment's end and shows a [notice].
 *
 * - Every segment is skipped at most once per viewing: after a skip, seeking back into it plays it (and a jump
 *   that landed a little short can never loop). Once the video has ended, a replay skips again ([onReplay]).
 * - [undoAllowed] (the adult, `Features.undoSponsorSkip`): the notice offers «تراجع عن التخطي» for
 *   [UNDO_WINDOW_MS]; [undo] goes back to the segment's start and never skips it again. A child only sees
 *   the message, for [NOTICE_MS].
 * - Nothing is skipped in a live stream, nor a segment shorter than [MIN_SEGMENT_MS], nor one that ends within
 *   [END_GUARD_MS] of the video's end (the jump would end the video: its end card, lock and playlist would
 *   follow, and an undo could not bring it back).
 * - Segments that touch or overlap (up to [MERGE_GAP_MS] apart) are one jump and one notice.
 * - "mute" segments silence the sound while playback is inside them.
 *
 * Main thread only.
 */
class SkipController(
    val undoAllowed: Boolean,
    /** Elapsed milliseconds (a clock that never jumps). */
    private val clock: () -> Long,
) {
    private var segments: List<SponsorSegment> = emptyList()

    /** Skipped during this visit: never again (until a replay). */
    private val skipped = HashSet<String>()

    /** Taken back by the adult: they play, now and after a replay. */
    private val suppressed = HashSet<String>()

    /** The player this controller muted, if any (a new player starts with its sound on). */
    private var mutedOn: SkipTarget? = null
    private var nextNoticeId = 0L

    private val _notice = MutableStateFlow<SkipNotice?>(null)
    val notice: StateFlow<SkipNotice?> = _notice.asStateFlow()

    /** The segments in force (the selected categories), sorted by start. */
    val current: List<SponsorSegment> get() = segments

    fun setSegments(list: List<SponsorSegment>) {
        segments = list.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    /** One look at [target]: expire the notice, mute or unmute, and skip when playback entered a segment. */
    fun tick(target: SkipTarget) {
        // Most videos have no segments (or none selected): then the player is not even looked at.
        if (segments.isEmpty() && _notice.value == null && mutedOn == null) return
        val now = clock()
        if (target.isEnded) {
            // This viewing is over: whatever starts it again (the end card's replay, the controls' own button)
            // is a new viewing that skips again.
            skipped.clear()
            _notice.value = null
        }
        _notice.value?.let { if (now >= it.untilMs) _notice.value = null }
        if (target.isLive) {
            applyMute(target, false)
            return
        }
        val position = target.positionMs
        applyMute(target, segments.any { it.action == SegmentAction.MUTE && it.id !in suppressed && position in it })
        if (!target.isPlaying) return
        val duration = target.durationMs ?: return
        val first = segments.firstOrNull { position in it && isSkippable(it, duration) } ?: return

        val chain = mutableListOf(first)
        var landing = first.endMs
        while (true) {
            val next = segments.firstOrNull {
                it !in chain && isSkippable(it, duration) && it.startMs <= landing + MERGE_GAP_MS && it.endMs > landing
            } ?: break
            chain += next
            landing = next.endMs
        }
        chain.mapTo(skipped) { it.id }
        target.seekExactly(landing)
        _notice.value = SkipNotice(
            id = ++nextNoticeId,
            category = first.category,
            undoable = undoAllowed,
            fromMs = first.startMs,
            toMs = landing,
            shownAtMs = now,
            untilMs = now + if (undoAllowed) UNDO_WINDOW_MS else NOTICE_MS,
            segmentIds = chain.mapTo(LinkedHashSet()) { it.id },
        )
    }

    /**
     * «تراجع عن التخطي»: back to where the skipped part began, and it is never skipped again. False (and nothing
     * happens) for a child, or once the notice has gone.
     */
    fun undo(target: SkipTarget): Boolean {
        val shown = _notice.value ?: return false
        if (!shown.undoable || clock() >= shown.untilMs) return false
        suppressed += shown.segmentIds
        _notice.value = null
        target.seekExactly(shown.fromMs)
        return true
    }

    /** The notice goes (BACK while it offered the undo): the skip stays. */
    fun dismiss() {
        _notice.value = null
    }

    /** «إعادة التشغيل» from the end card: a new viewing skips again (except what the adult chose to watch). */
    fun onReplay() {
        skipped.clear()
        _notice.value = null
    }

    private fun isSkippable(segment: SponsorSegment, durationMs: Long): Boolean =
        segment.action == SegmentAction.SKIP &&
            segment.id !in skipped &&
            segment.id !in suppressed &&
            segment.lengthMs >= MIN_SEGMENT_MS &&
            durationMs - segment.endMs >= END_GUARD_MS

    /**
     * A new player starts with its sound on (after Home, a fresh extraction): it is silenced again when needed.
     * Once a mute is over, the player looked at now gets its sound back, whichever view of it muted it.
     */
    private fun applyMute(target: SkipTarget, mute: Boolean) {
        if (mute && mutedOn !== target) {
            target.setMuted(true)
            mutedOn = target
        } else if (!mute && mutedOn != null) {
            target.setMuted(false)
            mutedOn = null
        }
    }

    companion object {
        /** How often the player is looked at while the video is on screen (a few field reads). */
        const val TICK_MS = 250L

        /** How long «تم تخطي: …» stays without an undo (a child). */
        const val NOTICE_MS = 3_000L

        /** How long the adult can take a skip back. */
        const val UNDO_WINDOW_MS = 5_000L

        const val MIN_SEGMENT_MS = 1_000L
        const val END_GUARD_MS = 1_000L
        const val MERGE_GAP_MS = 1_000L
    }
}

package com.ahmadnayfeh.shasha.lock

/**
 * The guard's timings (SPEC §4, "the guard outside the app").
 *
 * - [pollMs]: how often it looks at the app in front while something is enforced and Shasha is not in front
 *   (SPEC: every second).
 * - [fastPollMs] for [fastWindowMs] after Shasha went out of sight: pressing Home pauses our activity at
 *   once, and the other app's usage event lands a moment later; looking more often then is what keeps
 *   "Home → back" short. It costs nothing while Shasha is in front (no polling at all then).
 * - [rearmMs]: not back in front that long after a raise → raise again.
 * - [overlayTimeoutMs]: the cover removes itself that long after its last raise (a backstop if the guard
 *   itself stopped); every raise renews it, so it stays while raises go on…
 * - …but never longer than [maxCoverMs] for one return: if Shasha cannot come back, the TV stays usable.
 */
data class GuardTiming(
    val pollMs: Long = 1_000,
    val fastPollMs: Long = 200,
    val fastWindowMs: Long = 3_000,
    val rearmMs: Long = 2_500,
    val overlayTimeoutMs: Long = 3_000,
    val maxCoverMs: Long = 10_000,
)

/** The latest activity that came to the front (UsageStats), timed on the elapsed-realtime clock. */
data class FrontApp(val packageName: String, val className: String?, val atMs: Long)

/**
 * What the guard knows at one moment. Times are elapsed-realtime milliseconds.
 *
 * @property enforced the engine's `guardShouldEnforce` (a locked video, time up or outside the schedule).
 * @property enforcedSinceMs when [enforced] last became true.
 * @property appResumed Shasha's activity is resumed (MainActivity reports it): Shasha is in front.
 * @property appLeftAtMs when Shasha's activity last paused (when the process started, if it never was
 *   resumed since; null: unknown).
 * @property front the latest app in front per UsageStats; null when unknown.
 */
data class GuardInput(
    val nowMs: Long,
    val enforced: Boolean,
    val enforcedSinceMs: Long,
    val appResumed: Boolean,
    val appLeftAtMs: Long?,
    val screenOn: Boolean,
    val front: FrontApp?,
)

/** What to do now. [nextCheckMs] null: nothing to watch until something changes (enforcement, visibility, screen). */
data class GuardStep(
    val showOverlay: Boolean = false,
    val hideOverlay: Boolean = false,
    val launchApp: Boolean = false,
    val returned: GuardReturn? = null,
    val nextCheckMs: Long? = null,
)

/**
 * One return measured, from the moment Shasha lost the front ([backMs] is SPEC's "Home → back" number,
 * acceptance ≤ 2 s). [fromPackage] is what was in front ("" when unknown).
 */
data class GuardReturn(val fromPackage: String, val detectMs: Long, val coverMs: Long?, val backMs: Long)

/**
 * The guard's decisions, free of Android: given what is in front and whether anything is enforced, when to
 * cover the screen, when to bring Shasha back, and what the return took.
 *
 * Another app in front while enforced → cover it at once and start Shasha's activity; not back after
 * [GuardTiming.rearmMs] → start it again (and renew the cover); back → uncover and measure. Shasha's own
 * package is never "another app", nor are the system's own screens ([IGNORED_PACKAGES]) and the screensaver
 * ([DREAM_ACTIVITY]): pressing any key ends it and returns to what was in front.
 *
 * Except a stale own entry: when the newest resume UsageStats knows is Shasha's own from before its activity
 * paused, and [GuardTiming.fastWindowMs] went by without another, something that leaves no usage event is
 * in front (an HDMI input, a panel), or the events were missed: that is another app too. Within that window
 * the other app's event is simply late (Home was just pressed), or the screensaver's is on its way.
 *
 * Not thread-safe: one caller (the guard's loop, on the main thread).
 */
class GuardDecider(private val ownPackage: String, private val timing: GuardTiming = GuardTiming()) {
    private var pending: Pending? = null

    /** A return under way: another app took the front at [leftAtMs] and was seen at [detectedAtMs]. */
    private class Pending(val fromPackage: String, val leftAtMs: Long, val detectedAtMs: Long) {
        var lastLaunchAtMs = detectedAtMs
        var coverStartedAtMs: Long? = null
        var coveredAtMs: Long? = null
    }

    /** True while Shasha is being brought back. */
    val returning: Boolean get() = pending != null

    fun step(input: GuardInput): GuardStep {
        val current = pending
        if (!input.enforced || !input.screenOn) {
            // Nothing to guard (or nothing to see): uncover, forget the return. A screen that is off is woken
            // by its own broadcast, and the engine's state by its flow: no polling meanwhile.
            pending = null
            return GuardStep(hideOverlay = true)
        }
        if (input.appResumed) {
            pending = null
            val returned = current?.let {
                GuardReturn(it.fromPackage, it.detectedAtMs - it.leftAtMs, it.coveredAtMs?.minus(it.leftAtMs), input.nowMs - it.leftAtMs)
            }
            return GuardStep(hideOverlay = true, returned = returned)
        }
        val front = input.front
        if (current == null) {
            // Our own activity (its pause was reported before the other app's usage event arrived), or a
            // system screen: keep looking. An unknown front while Shasha is not resumed is another app, and
            // so is a stale own entry.
            if (front != null && !isOtherApp(front) && !isStaleOwn(front, input)) return GuardStep(nextCheckMs = pollDelay(input))
            val tookFrontAt = front?.atMs ?: input.nowMs
            val left = maxOf(input.appLeftAtMs ?: tookFrontAt, input.enforcedSinceMs).coerceAtMost(input.nowMs)
            val started = Pending(front?.packageName.orEmpty(), leftAtMs = left, detectedAtMs = input.nowMs)
            pending = started
            return raise(started, input.nowMs)
        }
        // Not back yet: raise again once the last raise had its time.
        if (input.nowMs - current.lastLaunchAtMs >= timing.rearmMs) return raise(current, input.nowMs)
        return GuardStep(nextCheckMs = untilRearm(current, input.nowMs))
    }

    /** The cover was shown at [atMs] (it may take a moment or fail: only a shown cover counts). */
    fun onCovered(atMs: Long) {
        val current = pending ?: return
        if (current.coveredAtMs == null) current.coveredAtMs = atMs
    }

    private fun raise(pending: Pending, nowMs: Long): GuardStep {
        pending.lastLaunchAtMs = nowMs
        val coverStart = pending.coverStartedAtMs ?: nowMs.also { pending.coverStartedAtMs = it }
        // Each raise renews the cover until one return has been covered for maxCoverMs; after that the cover
        // is left to expire and only the activity start is repeated.
        val cover = nowMs - coverStart < timing.maxCoverMs
        return GuardStep(showOverlay = cover, launchApp = true, nextCheckMs = untilRearm(pending, nowMs))
    }

    private fun untilRearm(pending: Pending, nowMs: Long): Long =
        (pending.lastLaunchAtMs + timing.rearmMs - nowMs).coerceIn(MIN_STEP_MS, timing.rearmMs)

    private fun pollDelay(input: GuardInput): Long {
        val left = input.appLeftAtMs
        return if (left != null && input.nowMs - left < timing.fastWindowMs) timing.fastPollMs else timing.pollMs
    }

    private fun isOtherApp(front: FrontApp): Boolean =
        front.packageName != ownPackage && front.packageName !in IGNORED_PACKAGES && front.className != DREAM_ACTIVITY

    /** Shasha's own resume from before its activity last paused, [GuardTiming.fastWindowMs] ago or more. */
    private fun isStaleOwn(front: FrontApp, input: GuardInput): Boolean {
        val left = input.appLeftAtMs ?: return false
        return front.packageName == ownPackage && front.atMs <= left && input.nowMs - left >= timing.fastWindowMs
    }

    companion object {
        /** The framework's own screens (the app chooser, shutdown…): not apps to watch videos in. */
        val IGNORED_PACKAGES = setOf("android")

        /** Hosts every screensaver (Android 11+), in the dream's package: the screensaver is not an app. */
        const val DREAM_ACTIVITY = "android.service.dreams.DreamActivity"

        private const val MIN_STEP_MS = 50L
    }
}

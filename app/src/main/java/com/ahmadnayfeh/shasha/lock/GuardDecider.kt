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
 * @property screenOn the screen is on (PowerManager.isInteractive).
 * @property front the latest app in front per UsageStats; null when unknown.
 * @property dreaming the screensaver is showing (ACTION_DREAMING_STARTED until ACTION_DREAMING_STOPPED): as
 *   with the screen off there is nothing to guard. It hides everything below it, and no activity can come in
 *   front of it (Android keeps the dream on top).
 * @property activeSinceMs when the screen last became usable: turned on, or the screensaver ended (null: not
 *   since the guard started). Shasha's last pause is often older (the TV went to standby, or the screensaver
 *   started over it): a return is never measured from before this, and the wait for a late usage event
 *   counts from here.
 */
data class GuardInput(
    val nowMs: Long,
    val enforced: Boolean,
    val enforcedSinceMs: Long,
    val appResumed: Boolean,
    val appLeftAtMs: Long?,
    val screenOn: Boolean,
    val front: FrontApp?,
    val dreaming: Boolean = false,
    val activeSinceMs: Long? = null,
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
 * ([DREAM_ACTIVITY]): pressing any key ends it and returns to what was in front. When one of those comes to
 * the front during a return, the return is dropped (nothing can be brought in front of a screensaver), and
 * the next app to come after it is measured from its own start.
 *
 * Except a stale own entry: when the newest resume UsageStats knows is Shasha's own from before its activity
 * paused, and [GuardTiming.fastWindowMs] went by without another, something that leaves no usage event is
 * in front (an HDMI input, a panel), or the events were missed: that is another app too. Within that window
 * the other app's event is simply late (Home was just pressed), or the screensaver's is on its way. No usage
 * event at all is treated the same way. The window starts when Shasha left or when the screen became usable
 * ([GuardInput.activeSinceMs]), whichever is later: waking the TV into Shasha itself is not a return.
 *
 * Not thread-safe: one caller (the guard's loop, on the main thread).
 */
class GuardDecider(private val ownPackage: String, private val timing: GuardTiming = GuardTiming()) {
    private var pending: Pending? = null

    /** When the latest screensaver or system screen seen in front came there (reset when Shasha is back). */
    private var neutralAtMs: Long? = null

    /**
     * A return under way: [fromPackage] ("" when unknown) took the front at [leftAtMs] and was seen at
     * [detectedAtMs].
     */
    private class Pending(val fromPackage: String, val leftAtMs: Long, val detectedAtMs: Long) {
        var lastLaunchAtMs = detectedAtMs
        var coverStartedAtMs: Long? = null
        var coveredAtMs: Long? = null
    }

    /** True while Shasha is being brought back. */
    val returning: Boolean get() = pending != null

    fun step(input: GuardInput): GuardStep {
        val current = pending
        if (!input.enforced || !input.screenOn || input.dreaming) {
            // Nothing to guard, or nothing to see (the screen off, or the screensaver over everything): uncover,
            // forget the return. Their broadcasts wake the guard, and the engine's state its flow: no polling
            // meanwhile.
            forget()
            return GuardStep(hideOverlay = true)
        }
        if (input.appResumed) {
            forget()
            val returned = current?.let {
                GuardReturn(it.fromPackage, it.detectedAtMs - it.leftAtMs, it.coveredAtMs?.minus(it.leftAtMs), input.nowMs - it.leftAtMs)
            }
            return GuardStep(hideOverlay = true, returned = returned)
        }
        val front = input.front
        val since = departure(input)
        if (front != null && isNeutral(front)) {
            // The screensaver or a system screen (the chooser…) in front, not an app to watch videos in: keep
            // looking. What comes after it is measured from its own start.
            if (since == null || front.atMs >= since) neutralAtMs = front.atMs
            // It came over the app Shasha is being brought back from (without the screensaver's broadcast):
            // nothing can be brought in front of it. Drop the return.
            if (current != null && front.atMs > current.leftAtMs) {
                pending = null
                return GuardStep(hideOverlay = true, nextCheckMs = pollDelay(input, since))
            }
        }
        if (current != null) {
            // Not back yet: raise again once the last raise had its time.
            if (input.nowMs - current.lastLaunchAtMs >= timing.rearmMs) return raise(current, input.nowMs)
            return GuardStep(nextCheckMs = untilRearm(current, input.nowMs))
        }
        // Our own activity on its way back (its pause was reported before the other app's usage event arrived),
        // or a neutral screen: keep looking. No usage event, or only Shasha's own from before its pause: the
        // other app's event may be late, for a moment only.
        val otherApp = when {
            front == null -> pastWindow(input, since)
            front.packageName == ownPackage -> isStaleOwn(front, input) && pastWindow(input, since)
            else -> !isNeutral(front)
        }
        if (!otherApp) return GuardStep(nextCheckMs = pollDelay(input, since))
        // The app in front, when UsageStats saw it (not Shasha's own stale entry, not "nothing").
        val known = front?.takeIf { it.packageName != ownPackage }
        var left = maxOf(since ?: known?.atMs ?: input.nowMs, input.enforcedSinceMs)
        // A neutral screen came between Shasha and this app: the app did not replace Shasha, it came later.
        val afterNeutral = neutralAtMs?.let { since == null || it >= since } == true
        if (known != null && afterNeutral) left = maxOf(left, known.atMs)
        val started = Pending(known?.packageName.orEmpty(), leftAtMs = left.coerceAtMost(input.nowMs), detectedAtMs = input.nowMs)
        pending = started
        neutralAtMs = null
        return raise(started, input.nowMs)
    }

    /** The cover was shown at [atMs] (it may take a moment or fail: only a shown cover counts). */
    fun onCovered(atMs: Long) {
        val current = pending ?: return
        if (current.coveredAtMs == null) current.coveredAtMs = atMs
    }

    private fun forget() {
        pending = null
        neutralAtMs = null
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

    /**
     * Since when Shasha is out of sight as far as anyone could see: its pause, or the screen becoming usable
     * after it (the pause may be from before standby or the screensaver). Null: unknown.
     */
    private fun departure(input: GuardInput): Long? = listOfNotNull(input.appLeftAtMs, input.activeSinceMs).maxOrNull()

    private fun pastWindow(input: GuardInput, since: Long?): Boolean = since == null || input.nowMs - since >= timing.fastWindowMs

    private fun pollDelay(input: GuardInput, since: Long?): Long =
        if (since != null && input.nowMs - since < timing.fastWindowMs) timing.fastPollMs else timing.pollMs

    /** The screensaver or one of the system's own screens: never an app to bring Shasha back from. */
    private fun isNeutral(front: FrontApp): Boolean = front.packageName in IGNORED_PACKAGES || front.className == DREAM_ACTIVITY

    /** Shasha's own resume from before its activity last paused. */
    private fun isStaleOwn(front: FrontApp, input: GuardInput): Boolean {
        val left = input.appLeftAtMs ?: return false
        return front.packageName == ownPackage && front.atMs <= left
    }

    companion object {
        /** The framework's own screens (the app chooser, shutdown…): not apps to watch videos in. */
        val IGNORED_PACKAGES = setOf("android")

        /** Hosts every screensaver (Android 11+), in the dream's package: the screensaver is not an app. */
        const val DREAM_ACTIVITY = "android.service.dreams.DreamActivity"

        private const val MIN_STEP_MS = 50L
    }
}

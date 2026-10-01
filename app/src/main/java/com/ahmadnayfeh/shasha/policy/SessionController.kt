package com.ahmadnayfeh.shasha.policy

import android.util.Log
import com.ahmadnayfeh.shasha.data.OverridesStore
import com.ahmadnayfeh.shasha.data.UsageLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The current profile's [SessionState] (SPEC §3: daily quota, schedule, the quiet notice 5 minutes
 * before the end, "time up"), always computed by [PolicyEngine.session] from the stored usage and the
 * parent's grants.
 *
 * It is recomputed every [tickMs] (earlier when the state changes before that, e.g. at bedtime) and at
 * once on any change: profile switch or edit, a grant, recorded usage. The usage itself is recorded by
 * [UsageTracker] every 15 s, so "time up" comes at most that late.
 *
 * @param onNewDay runs at the first computation and whenever the local day changes (daily cleanup).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionController(
    private val engine: PolicyEngine,
    private val profile: StateFlow<Profile?>,
    private val usage: UsageLedger,
    private val overrides: OverridesStore,
    private val scope: CoroutineScope,
    private val tickMs: Long = TICK_MS,
    private val onNewDay: suspend (epochDay: Long) -> Unit = {},
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Ok(null))
    private val _ready = MutableStateFlow(false)
    private val _warnings = MutableSharedFlow<SessionState.Warning>(extraBufferCapacity = 8)

    /**
     * The current profile's state. Until [isReady] it is a stand-in (Ok without limit): decide with
     * [current], and let the UI wait for [isReady] before it shows "time up" or hides it.
     */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** False until the first computation (at start, while the current profile is being loaded). */
    val isReady: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * One event each time the profile enters [SessionState.Warning] (not on every recomputation while it
     * stays there): show the quiet notice once. Also after a restart or a switch into a profile that is
     * already in its last minutes.
     */
    val warningEvents: SharedFlow<SessionState.Warning> = _warnings.asSharedFlow()

    private val computing = Mutex()
    private val granting = Mutex()
    private var computedFor: String? = null
    private var lastDay: Long? = null
    private var job: Job? = null

    /** Starts following the profile, the grants, the usage and the clock. Once; later calls do nothing. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            merge(profile.filterNotNull().map { }, overrides.overrides.map { }, usage.changes, ticks())
                .conflate()
                .collect {
                    try {
                        refresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The next trigger (at the latest the next tick) tries again.
                        Log.w(TAG, "Could not compute the session state", e)
                    }
                }
        }
    }

    /** The state right now, freshly computed: what decisions use (waits for the profile at start). */
    suspend fun current(): SessionState {
        profile.filterNotNull().first()
        return refresh() ?: _state.value
    }

    /** Recomputes now and publishes the result; null while no profile is loaded. */
    suspend fun refresh(): SessionState? = computing.withLock {
        val p = profile.value ?: return@withLock null
        val today = engine.today()
        val next = engine.session(p, usage.foregroundSeconds(p.id, today), overrides.current())
        publish(p.id, next)
        if (today != lastDay) {
            lastDay = today
            scope.launch { onNewDay(today) }
        }
        next
    }

    /**
     * The parent's unlock for [minutes] from now (after the PIN on the TV, or from the phone's parent
     * section): works whether the quota, the schedule or both stop the current profile
     * ([PolicyEngine.grantUnlock]). Returns the new state.
     * @throws IllegalArgumentException when [minutes] is not positive.
     */
    suspend fun grantUnlock(minutes: Int): SessionState = granting.withLock {
        require(minutes > 0) { "minutes must be > 0, was $minutes" }
        val p = profile.filterNotNull().first()
        val today = engine.today()
        val grants = engine.grantUnlock(p, usage.foregroundSeconds(p.id, today), overrides.current(), minutes)
        overrides.add(grants, today, engine.nowEpochMs())
        refresh() ?: _state.value
    }

    /**
     * The phone's «+N» ([minutes] more than what is left now): while the profile may still watch, its time grows by
     * [minutes] on top of the quota or the bedtime, whichever ends it first ([grantUnlock] of the whole); when nothing
     * is left it is exactly [grantUnlock]. A profile without any limit gets nothing.
     */
    suspend fun addTime(minutes: Int): SessionState = granting.withLock {
        require(minutes > 0) { "minutes must be > 0, was $minutes" }
        val p = profile.filterNotNull().first()
        val today = engine.today()
        val used = usage.foregroundSeconds(p.id, today)
        val current = overrides.current()
        val leftSeconds = when (val now = engine.session(p, used, current)) {
            is SessionState.Ok -> now.remainingSeconds ?: return@withLock refresh() ?: _state.value
            is SessionState.Warning -> now.remainingSeconds
            SessionState.TimeUp, is SessionState.OutsideSchedule -> 0L
        }
        val total = ((leftSeconds.coerceAtLeast(0) + minutes * 60L + 59) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        overrides.add(engine.grantUnlock(p, used, current, total), today, engine.nowEpochMs())
        refresh() ?: _state.value
    }

    /**
     * The parent's "for the rest of today" ([PolicyEngine.grantRestOfToday]): no quota until midnight,
     * and when the schedule is what stops the profile now, no schedule until the end of the day either.
     */
    suspend fun unlimitedToday(): SessionState = granting.withLock {
        val p = profile.filterNotNull().first()
        overrides.add(engine.grantRestOfToday(p, overrides.current()), engine.today(), engine.nowEpochMs())
        refresh() ?: _state.value
    }

    /** Inside [computing]. */
    private fun publish(profileId: String, next: SessionState) {
        val entersWarning = next is SessionState.Warning &&
            (!_ready.value || computedFor != profileId || _state.value !is SessionState.Warning)
        computedFor = profileId
        _state.value = next
        _ready.value = true
        if (entersWarning) _warnings.tryEmit(next as SessionState.Warning)
    }

    /**
     * Every [tickMs], or sooner when the state says it changes sooner (bedtime, the end of a schedule
     * lift). The wait restarts with every new state, so it is always measured from the latest one.
     */
    private fun ticks(): Flow<Unit> = _state.transformLatest { state ->
        while (true) {
            delay(nextTickMs(state))
            emit(Unit)
        }
    }

    private fun nextTickMs(state: SessionState): Long {
        val untilChangeMs = when (state) {
            is SessionState.Ok -> state.remainingSeconds?.let { it * 1000 }
            is SessionState.Warning -> state.remainingSeconds * 1000
            is SessionState.OutsideSchedule -> state.nextAllowedAtEpochMs - engine.nowEpochMs()
            SessionState.TimeUp -> null
        }
        return if (untilChangeMs == null) tickMs else minOf(tickMs, maxOf(MIN_TICK_MS, untilChangeMs))
    }

    companion object {
        const val TICK_MS = 15_000L
        private const val MIN_TICK_MS = 1_000L
        private const val TAG = "SessionController"
    }
}

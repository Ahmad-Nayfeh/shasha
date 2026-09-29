package com.ahmadnayfeh.shasha.lock

import com.ahmadnayfeh.shasha.policy.LockState
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the guard must do now. [enforce]: keep Shasha in front ([PolicyEngine.guardShouldEnforce]). [child]: that
 * is (also) because a child profile is current, so the cover says how a child gets out.
 */
data class GuardDemand(val enforce: Boolean, val child: Boolean)

/**
 * The guard's input (SPEC §4 and the owner's decision of the final phase): a locked video, "time up", outside the
 * schedule, or a CHILD profile being current at all, until the parent PIN lets it out ([childLetOut]: its «خروج»,
 * while the picker chooses who comes next). The adult profile is free apart from its own lock and time limits.
 */
fun guardDemand(
    engine: PolicyEngine,
    lock: Flow<PlaybackLock?>,
    session: Flow<SessionState>,
    profile: Flow<Profile?>,
    childLetOut: Flow<Boolean>,
): Flow<GuardDemand> = combine(lock, session, profile, childLetOut) { current, state, who, letOut ->
    val lockState = LockState.of(current, state, who, letOut)
    GuardDemand(enforce = engine.guardShouldEnforce(lockState), child = lockState.childProfile)
}.distinctUntilChanged()

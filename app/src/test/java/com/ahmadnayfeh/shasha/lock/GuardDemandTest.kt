package com.ahmadnayfeh.shasha.lock

import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The guard's input (owner decision of the final phase): a current CHILD profile is kept inside the app the whole
 * time, until the parent PIN lets it out; the adult is free apart from its own lock and time limits; a lock or the
 * time still hold on their own.
 */
class GuardDemandTest {
    private val engine = PolicyEngine(MutableClock(instantAt("2026-09-28T12:00")))
    private val lock = MutableStateFlow<PlaybackLock?>(null)
    private val session = MutableStateFlow<SessionState>(SessionState.Ok(null))
    private val profile = MutableStateFlow<Profile?>(null)
    private val letOut = MutableStateFlow(false)
    private val demands = guardDemand(engine, lock, session, profile, letOut)

    private fun now(): GuardDemand = runBlocking { demands.first() }

    @Test
    fun aChildProfile_isGuardedWithoutAnyLock_untilTheParentLetsItOut() {
        assertThat(now()).isEqualTo(GuardDemand(enforce = false, child = false)) // the profile is still loading

        val child = kid()
        profile.value = child
        assertThat(now()).isEqualTo(GuardDemand(enforce = true, child = true))

        // «خروج» + the parent PIN: the picker chooses who comes next, the TV is free meanwhile.
        letOut.value = true
        assertThat(now()).isEqualTo(GuardDemand(enforce = false, child = false))
        // A lock or the time still keep the TV on Shasha (and the cover no longer speaks of the child profile).
        lock.value = engine.lockPlayback(child, "vid00000001")
        assertThat(now()).isEqualTo(GuardDemand(enforce = true, child = false))
        lock.value = null
        session.value = SessionState.TimeUp
        assertThat(now()).isEqualTo(GuardDemand(enforce = true, child = false))
    }

    @Test
    fun theAdult_isFreeApartFromItsOwnLockAndTime() {
        val adult = owner()
        profile.value = adult
        assertThat(now().enforce).isFalse()
        lock.value = engine.lockPlayback(adult, "vid00000001")
        assertThat(now()).isEqualTo(GuardDemand(enforce = true, child = false))
        lock.value = null
        session.value = SessionState.OutsideSchedule(0)
        assertThat(now().enforce).isTrue()
        session.value = SessionState.Ok(null)
        // A stale let-out (a child left earlier) means nothing for the adult.
        letOut.value = true
        assertThat(now().enforce).isFalse()
    }
}

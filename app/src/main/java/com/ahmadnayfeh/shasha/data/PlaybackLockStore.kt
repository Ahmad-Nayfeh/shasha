package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.PolicyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The in-app playback lock of SPEC §4 ([PlaybackLock]), persisted so that killing the app, pulling the
 * plug or a reboot keeps it. The engine decides every change (lockPlayback, onVideoEnded, unlockWithPin,
 * exitWithChallenge); this only stores the result. Phase 4 (lock and guard) drives it; the PolicyGate
 * already refuses to start anything else while it holds a lock.
 */
class PlaybackLockStore(private val store: DataStore<Preferences>, scope: CoroutineScope) {

    /** Null while nothing is locked, and for a moment at start until the file is read (use [current] to decide). */
    val lock: StateFlow<PlaybackLock?> = store.data.map(::read).distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): PlaybackLock? = read(store.data.first())

    /** Stores the engine's new lock; null ends it. */
    suspend fun set(lock: PlaybackLock?) {
        store.edit { prefs ->
            if (lock == null) {
                prefs.remove(PolicyStore.PLAYBACK_LOCK)
            } else {
                prefs[PolicyStore.PLAYBACK_LOCK] = PolicyJson.json.encodeToString(PlaybackLock.serializer(), lock)
            }
        }
    }

    private fun read(prefs: Preferences): PlaybackLock? =
        prefs[PolicyStore.PLAYBACK_LOCK]?.let { PolicyJson.decodeOrNull(PlaybackLock.serializer(), it) }
}

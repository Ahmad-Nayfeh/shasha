package com.ahmadnayfeh.shasha.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ahmadnayfeh.shasha.policy.Overrides
import com.ahmadnayfeh.shasha.policy.PolicyJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where the parent's grants are kept ([OverridesRepository]; tests use a fake). */
interface OverridesStore {
    /** Every stored grant; the engine picks those of the profile that still apply. */
    val overrides: Flow<Overrides>

    suspend fun current(): Overrides

    /** Adds [grants] and drops what can no longer apply ([today] is the local epoch day). */
    suspend fun add(grants: Overrides, today: Long, nowEpochMs: Long)
}

/**
 * The parent's grants (extra time, "rest of today", schedule lifts: [Overrides]), stored whole in
 * DataStore so a grant and the pruning of old ones happen in one write. Grants end by themselves (at
 * midnight, or at their time), so the list stays small: every write prunes it, and [prune] runs daily.
 */
class OverridesRepository(private val store: DataStore<Preferences>) : OverridesStore {

    override val overrides: Flow<Overrides> = store.data.map(::read).distinctUntilChanged()

    override suspend fun current(): Overrides = read(store.data.first())

    override suspend fun add(grants: Overrides, today: Long, nowEpochMs: Long) {
        if (grants == Overrides.NONE) return
        store.edit { prefs -> write(prefs, (read(prefs) + grants).pruned(today, nowEpochMs)) }
    }

    /**
     * Drops grants that can no longer apply, and those of profiles not in [profileIds] (deleted ones)
     * when it is given.
     */
    suspend fun prune(today: Long, nowEpochMs: Long, profileIds: Set<String>? = null) {
        store.edit { prefs ->
            val kept = read(prefs).pruned(today, nowEpochMs).let { o ->
                if (profileIds == null) o else Overrides(o.quota.filter { it.profileId in profileIds }, o.schedule.filter { it.profileId in profileIds })
            }
            write(prefs, kept)
        }
    }

    // An unreadable value counts as "no grants": lost extra time, never extra time nobody gave.
    private fun read(prefs: Preferences): Overrides =
        prefs[PolicyStore.OVERRIDES]?.let { PolicyJson.decodeOrNull(Overrides.serializer(), it) } ?: Overrides.NONE

    private fun write(prefs: MutablePreferences, overrides: Overrides) {
        if (overrides == Overrides.NONE) {
            prefs.remove(PolicyStore.OVERRIDES)
        } else {
            prefs[PolicyStore.OVERRIDES] = PolicyJson.json.encodeToString(Overrides.serializer(), overrides)
        }
    }
}

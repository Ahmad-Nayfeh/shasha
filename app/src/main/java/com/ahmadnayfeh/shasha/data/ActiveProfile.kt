package com.ahmadnayfeh.shasha.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.ahmadnayfeh.shasha.policy.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The profile the TV is in now (SPEC §3). Its id is persisted, so a restart or a reboot comes back to
 * the same profile: turning the TV off and on does not take a child out of the child profile.
 * History, progress, watch later and usage are all kept under [id].
 *
 * Switch profiles only through `PolicyGate.switchProfile`, which asks for the parent PIN where the
 * engine says so; [switchTo] itself checks nothing.
 *
 * Create it with [create]. [id] is right from the first read (never a stand-in that would show the
 * owner's history to a child for a moment); [profile] is null until the profiles are loaded, then
 * always a stored profile: when the current one disappears (deleted), the owner's profile takes over.
 */
class ActiveProfile private constructor(
    private val profiles: ProfileRepository,
    private val store: DataStore<Preferences>,
    private val ownerName: String,
    initialId: String,
) : CurrentProfile {

    private val mutex = Mutex()
    private val _id = MutableStateFlow(initialId)
    private val _profile = MutableStateFlow<Profile?>(null)

    override val id: StateFlow<String> = _id.asStateFlow()

    /** Null only until the profiles are loaded at start. Follows switches and edits of the current profile. */
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    /** The current profile, waiting for the first load if needed. */
    suspend fun awaitProfile(): Profile = profile.filterNotNull().first()

    /**
     * Makes [profileId] current and remembers it. No PIN is checked here (see the class comment).
     * @return false when there is no such profile.
     */
    suspend fun switchTo(profileId: String): Boolean = mutex.withLock {
        val target = profiles.get(profileId) ?: return@withLock false
        persist(profileId)
        _profile.value = target
        _id.value = profileId
        true
    }

    /** Keeps [profile] in step with the stored profiles: edits apply at once, a deleted current profile falls back to the owner. */
    private fun start(scope: CoroutineScope): Job = scope.launch {
        try {
            profiles.ensureOwner(ownerName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the owner's profile", e)
        }
        profiles.observeAll().collect { all -> mutex.withLock { follow(all) } }
    }

    private suspend fun follow(all: List<Profile>) {
        val current = all.firstOrNull { it.id == _id.value }
        if (current != null) {
            _profile.value = current
            return
        }
        val owner = all.firstOrNull { it.isAdult } ?: return // ensureOwner is on its way
        persist(owner.id)
        _profile.value = owner
        _id.value = owner.id
    }

    private suspend fun persist(profileId: String) {
        store.edit { it[PolicyStore.CURRENT_PROFILE_ID] = profileId }
    }

    companion object {
        private const val TAG = "ActiveProfile"

        /**
         * Reads the stored id, blocking the calling thread for it (a few bytes: ShashaApp reads it off the
         * main thread at start, so the UI rarely waits), and starts following the stored profiles on [scope].
         *
         * @param ownerName the name of the owner's profile when the first start creates it.
         * @param defaultId the profile of a first start (nothing stored yet): the owner.
         */
        fun create(
            profiles: ProfileRepository,
            store: DataStore<Preferences>,
            scope: CoroutineScope,
            ownerName: String,
            defaultId: String = ADULT_PROFILE_ID,
        ): ActiveProfile {
            val stored = try {
                runBlocking { store.data.first()[PolicyStore.CURRENT_PROFILE_ID] }
            } catch (e: Exception) {
                // An unreadable store cannot tell whose TV session this was; the owner's is the one that exists.
                Log.e(TAG, "Could not read the current profile", e)
                null
            }
            return ActiveProfile(profiles, store, ownerName, stored ?: defaultId).also { it.start(scope) }
        }
    }
}

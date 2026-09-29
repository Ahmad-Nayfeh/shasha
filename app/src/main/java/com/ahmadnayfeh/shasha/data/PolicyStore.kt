package com.ahmadnayfeh.shasha.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The small policy values kept in DataStore ("policy.preferences_pb"), each written whole: which profile
 * is current, the parent PIN, the PIN lockout, the parent's grants and the playback lock. Profiles and
 * usage live in Room. One file, one DataStore instance per process (AppContainer owns it).
 */
object PolicyStore {
    const val FILE_NAME = "policy"

    val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
    val PARENT_PIN_HASH = stringPreferencesKey("parent_pin_hash")
    val PIN_ATTEMPTS = stringPreferencesKey("pin_attempts")
    val OVERRIDES = stringPreferencesKey("overrides")
    val PLAYBACK_LOCK = stringPreferencesKey("playback_lock")

    /**
     * A damaged file starts over empty instead of crashing every start. DataStore writes a temporary file
     * and renames it, so this takes a failing disk; the cost is a PIN setup and a profile choice again.
     */
    fun create(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
        )
}

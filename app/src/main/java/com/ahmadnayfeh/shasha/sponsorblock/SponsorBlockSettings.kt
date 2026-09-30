package com.ahmadnayfeh.shasha.sponsorblock

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.backup.SettingType
import com.ahmadnayfeh.shasha.backup.SettingsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * SPEC §7 «فئات SponsorBlock» in its own small DataStore file ("sponsorblock.preferences_pb"): which categories
 * are skipped. Nothing chosen yet = SPEC §1's defaults; an empty choice turns SponsorBlock off (nothing is even
 * asked for). The whole household shares the choice: a child's videos skip what the adult's do.
 */
class SponsorBlockSettings(private val store: DataStore<Preferences>) {

    val categories: Flow<Set<SponsorCategory>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[CATEGORIES]?.let(::decode) ?: SponsorCategory.DEFAULTS }
        .distinctUntilChanged()

    suspend fun setEnabled(category: SponsorCategory, enabled: Boolean) {
        store.edit { prefs ->
            val current = prefs[CATEGORIES]?.let(::decode) ?: SponsorCategory.DEFAULTS
            prefs[CATEGORIES] = encode(if (enabled) current + category else current - category)
        }
    }

    suspend fun setCategories(categories: Set<SponsorCategory>) {
        store.edit { it[CATEGORIES] = encode(categories) }
    }

    /** Back to SPEC §1's defaults. */
    suspend fun reset() {
        store.edit { it.remove(CATEGORIES) }
    }

    companion object {
        const val FILE_NAME = "sponsorblock"

        private val CATEGORIES = stringSetPreferencesKey("categories")

        private fun encode(categories: Set<SponsorCategory>): Set<String> = categories.mapTo(sortedSetOf()) { it.apiKey }

        /** Keys this build does not know (a newer backup) are ignored. */
        private fun decode(keys: Set<String>): Set<SponsorCategory> =
            SponsorCategory.entries.filterTo(LinkedHashSet()) { it.apiKey in keys }

        /** One instance per process (AppContainer owns it); a damaged file starts over with the defaults. */
        fun createStore(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

        /** The manual backup keeps the choice (SPEC §7's import/export). */
        fun backupSection(store: DataStore<Preferences>) =
            SettingsSection(FILE_NAME, store, keys = mapOf(CATEGORIES.name to SettingType.STRING_SET))
    }
}

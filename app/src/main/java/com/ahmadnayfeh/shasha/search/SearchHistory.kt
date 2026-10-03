package com.ahmadnayfeh.shasha.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.youtube.safety.ExplicitFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** A profile's recent searches, newest first (at most [SearchHistory.MAX]). */
interface RecentSearches {
    fun observe(profileId: String): Flow<List<String>>

    suspend fun add(profileId: String, query: String)

    suspend fun clear(profileId: String)
}

/**
 * The recent searches of each profile, on the TV only ("search.preferences_pb"; not in the backup: a convenience,
 * like the feed cache). Only profiles with search keep any (a child has none: SPEC §3).
 */
class SearchHistory(private val store: DataStore<Preferences>) : RecentSearches {

    /** Without what the built-in filter holds back: a search kept before it existed is not offered again. */
    override fun observe(profileId: String): Flow<List<String>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decode(prefs[key(profileId)]).filterNot { ExplicitFilter.default.isExplicit(it) } }
        .distinctUntilChanged()

    /** [query] becomes the newest; the same text typed differently (case, spaces) replaces the older one. An explicit one is not kept. */
    override suspend fun add(profileId: String, query: String) {
        val clean = normalize(query)?.takeUnless { ExplicitFilter.default.isExplicit(it) } ?: return
        store.edit { prefs ->
            val list = decode(prefs[key(profileId)])
            prefs[key(profileId)] = encode((listOf(clean) + list.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX))
        }
    }

    override suspend fun clear(profileId: String) {
        store.edit { it.remove(key(profileId)) }
    }

    companion object {
        const val FILE_NAME = "search"
        const val MAX = 10

        /** Longer texts are not kept (a pasted paragraph is not a search to repeat). */
        private const val MAX_LENGTH = 120

        private fun key(profileId: String) = stringPreferencesKey("recent_$profileId")

        internal fun normalize(query: String): String? =
            query.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

        private fun decode(stored: String?): List<String> = stored?.split('\n')?.filter { it.isNotBlank() }?.take(MAX).orEmpty()

        private fun encode(list: List<String>): String = list.joinToString("\n")

        fun createStore(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )
    }
}

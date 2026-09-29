package com.ahmadnayfeh.shasha.lock

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One time the guard brought Shasha back (SPEC §4 acceptance: "Home → back" ≤ 2 s, measured on the TV).
 *
 * @property atEpochMs when Shasha was back (wall clock, for the list).
 * @property fromPackage the app that was in front ("" when unknown).
 * @property detectMs from leaving Shasha to the guard seeing the other app.
 * @property coverMs from leaving Shasha to the cover being shown; null when it could not be shown.
 * @property backMs from leaving Shasha to its screen being back (activity resumed): the number to report.
 */
@Serializable
data class ReturnTime(
    val atEpochMs: Long,
    val fromPackage: String,
    val detectMs: Long,
    val coverMs: Long?,
    val backMs: Long,
)

/**
 * The last [MAX] return times, newest first, in their own small DataStore file ("guard.preferences_pb"; one
 * instance per process, owned by AppContainer's [Guard]). Measurements only: nothing here decides anything.
 */
class GuardReturnTimes(private val store: DataStore<Preferences>) {

    /** Newest first, at most [MAX]. */
    val times: Flow<List<ReturnTime>> = store.data.map(::read).distinctUntilChanged()

    suspend fun current(): List<ReturnTime> = read(store.data.first())

    suspend fun add(time: ReturnTime) {
        store.edit { prefs -> prefs[KEY] = json.encodeToString(serializer, (listOf(time) + read(prefs)).take(MAX)) }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private fun read(prefs: Preferences): List<ReturnTime> {
        val text = prefs[KEY] ?: return emptyList()
        return try {
            json.decodeFromString(serializer, text).take(MAX)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unreadable return times, starting over", e)
            emptyList()
        }
    }

    companion object {
        const val MAX = 10
        const val FILE_NAME = "guard"
        private const val TAG = "GuardReturnTimes"
        private val KEY = stringPreferencesKey("return_times")
        private val serializer = ListSerializer(ReturnTime.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        /** The process's one instance of the file (a damaged file starts over empty: only measurements are lost). */
        fun create(context: Context): GuardReturnTimes = GuardReturnTimes(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            ),
        )
    }
}

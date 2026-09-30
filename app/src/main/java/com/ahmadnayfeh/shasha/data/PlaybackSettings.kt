package com.ahmadnayfeh.shasha.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.backup.SettingType
import com.ahmadnayfeh.shasha.backup.SettingsSection
import com.ahmadnayfeh.shasha.player.MAX_AUTO_HEIGHT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/**
 * SPEC §7 «الجودة الافتراضية» in its own small DataStore file ("playback.preferences_pb"): the highest quality
 * the player reaches by itself. Below it "Auto" adapts and falls back when the network slows down (SPEC §1:
 * prefer 1080p, the default). One choice for the whole household: the TV has one screen and one connection.
 *
 * [maxQuality] is read by the player when it starts a video, so it is a [StateFlow] kept by [scope]: the
 * default until the file is read, a few milliseconds after the first access (long before an extraction ends).
 */
class PlaybackSettings(private val store: DataStore<Preferences>, scope: CoroutineScope) {

    val maxQuality: StateFlow<Int> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decode(prefs[MAX_QUALITY]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_MAX_QUALITY)

    /** One of [QUALITY_CAPS]; the next video plays with it. */
    suspend fun setMaxQuality(height: Int) {
        require(height in QUALITY_CAPS) { "Not a quality cap: $height" }
        store.edit { it[MAX_QUALITY] = height }
    }

    /** Back to [DEFAULT_MAX_QUALITY] («إرجاع كل الإعدادات»). */
    suspend fun reset() {
        store.edit { it.remove(MAX_QUALITY) }
    }

    companion object {
        const val FILE_NAME = "playback"

        /** What Settings offers, best first: YouTube's names for the picture's short side ("1080p"). */
        val QUALITY_CAPS: List<Int> = listOf(2160, 1440, 1080, 720, 480)

        /** SPEC §1: 1080p, falling back automatically. */
        const val DEFAULT_MAX_QUALITY = MAX_AUTO_HEIGHT

        private val MAX_QUALITY = intPreferencesKey("max_quality")

        /** A value this build does not offer (a damaged file, a hand-edited backup) means the default. */
        internal fun decode(stored: Int?): Int = stored?.takeIf { it in QUALITY_CAPS } ?: DEFAULT_MAX_QUALITY

        /** One instance per process (AppContainer owns it); a damaged file starts over with the default. */
        fun createStore(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

        /** The manual backup keeps the choice (SPEC §7's import/export). */
        fun backupSection(store: DataStore<Preferences>) =
            SettingsSection(FILE_NAME, store, keys = mapOf(MAX_QUALITY.name to SettingType.INT))
    }
}

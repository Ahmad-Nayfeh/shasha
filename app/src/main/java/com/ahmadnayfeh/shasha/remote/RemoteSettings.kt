package com.ahmadnayfeh.shasha.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ahmadnayfeh.shasha.backup.SettingType
import com.ahmadnayfeh.shasha.backup.SettingsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Base64

/**
 * «التحكم من الجوال» (SPEC §5, §7) in its own small DataStore file ("remote.preferences_pb"): whether the
 * phone remote runs (on by default once the first start is done), the pairing token of the QR code, and
 * the port the server got last time (kept, so a saved link or share shortcut keeps working).
 */
class RemoteSettings(private val store: DataStore<Preferences>, private val random: SecureRandom = SecureRandom()) {

    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: true }.distinctUntilChanged()

    suspend fun setEnabled(on: Boolean) {
        store.edit { it[ENABLED] = on }
    }

    /** The pairing token, created (and stored) the first time it is asked for. */
    suspend fun token(): String {
        store.data.first()[TOKEN]?.takeIf(::isValidToken)?.let { return it }
        var token = ""
        store.edit { prefs ->
            // Another caller may have created it meanwhile: keep that one.
            token = prefs[TOKEN]?.takeIf(::isValidToken) ?: newToken().also { prefs[TOKEN] = it }
        }
        return token
    }

    /** A new token: every phone paired with the old one is refused from now on (none is paired with the new one yet). */
    suspend fun regenerateToken(): String {
        val token = newToken()
        store.edit {
            it[TOKEN] = token
            it.remove(PHONE_PAIRED)
        }
        return token
    }

    /**
     * v1.1.1: a phone has reached the server with the current token at least once (it scanned the QR code and opened the
     * page). Kept across restarts, forgotten with a new token ([regenerateToken]). Not in the backup.
     */
    val phonePaired: Flow<Boolean> = store.data.map { it[PHONE_PAIRED] ?: false }.distinctUntilChanged()

    suspend fun markPhonePaired() {
        store.edit { if (it[PHONE_PAIRED] != true) it[PHONE_PAIRED] = true }
    }

    /** The port to try first: the last one bound, else [DEFAULT_PORT]. */
    suspend fun preferredPort(): Int = store.data.first()[PORT]?.takeIf { it in PORT_RANGE } ?: DEFAULT_PORT

    suspend fun rememberPort(port: Int) {
        if (port in PORT_RANGE) store.edit { it[PORT] = port }
    }

    /** The port the parent chose (Settings → التحكم من الجوال); the server moves to it at its next start. */
    val port: Flow<Int> = store.data.map { it[PORT]?.takeIf { p -> p in PORT_RANGE } ?: DEFAULT_PORT }.distinctUntilChanged()

    /** @throws IllegalArgumentException outside [PORT_RANGE]. */
    suspend fun setPort(port: Int) {
        require(port in PORT_RANGE) { "Port $port outside $PORT_RANGE" }
        store.edit { it[PORT] = port }
    }

    /** Back to [DEFAULT_PORT] («إرجاع الإعداد الأصلي», «إرجاع كل الإعدادات»). */
    suspend fun resetPort() {
        store.edit { it.remove(PORT) }
    }

    /** 32 URL-safe characters (192 random bits, [SecureRandom]). */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val FILE_NAME = "remote"
        const val DEFAULT_PORT = 8765

        /** Ports a parent may choose: not the privileged ones. */
        val PORT_RANGE = 1024..65535
        private const val TOKEN_BYTES = 24
        const val TOKEN_LENGTH = 32

        private val ENABLED = booleanPreferencesKey("enabled")
        private val TOKEN = stringPreferencesKey("pairing_token")
        private val PORT = intPreferencesKey("port")
        private val PHONE_PAIRED = booleanPreferencesKey("phone_paired")

        private val TOKEN_CHARS = Regex("[A-Za-z0-9_-]{$TOKEN_LENGTH,}")

        fun isValidToken(token: String): Boolean = TOKEN_CHARS.matches(token)

        /** One instance per process (AppContainer owns it); a damaged file starts over (a new token: pair again). */
        fun createStore(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )

        /**
         * What the manual backup keeps of this file: the on/off choice and the port. Never the pairing token:
         * a backup file lying around must not let a phone control the TV (pair again after a restore).
         */
        fun backupSection(store: DataStore<Preferences>) =
            SettingsSection(FILE_NAME, store, keys = mapOf(ENABLED.name to SettingType.BOOLEAN, PORT.name to SettingType.INT))
    }
}

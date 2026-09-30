package com.ahmadnayfeh.shasha.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The type of one backed-up setting: its tag in the backup (`{"boolean": true}`, `{"int": 8765}`…). */
enum class SettingType(val tag: String) {
    BOOLEAN("boolean"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    STRING("string"),
    STRING_SET("stringSet"),
}

/**
 * Settings of one DataStore file that the manual backup keeps: only the [keys] named here, each with its
 * type, are written and restored. A backup can never set anything else (a pairing token, a key of the
 * wrong type that would break the screen reading it).
 *
 * A later settings screen (SPEC §7: SponsorBlock categories, the default quality) adds its own section.
 */
class SettingsSection(val name: String, private val store: DataStore<Preferences>, val keys: Map<String, SettingType>) {

    suspend fun export(): Map<String, JsonElement> {
        val prefs = store.data.first().asMap()
        return prefs.entries
            .mapNotNull { (key, value) -> keys[key.name]?.let { type -> encode(type, value)?.let { key.name to it } } }
            .toMap()
            .toSortedMap()
    }

    /** Replaces this section's keys with [values]: a key the backup does not have goes back to its default. */
    suspend fun restore(values: Map<String, JsonElement>) {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name in keys }.forEach { prefs.remove(it) }
            for ((name, element) in values) {
                val type = keys[name] ?: continue
                val raw = (element as? JsonObject)?.get(type.tag) ?: continue
                prefs.set(name, type, raw)
            }
        }
    }

    private fun MutablePreferences.set(name: String, type: SettingType, raw: JsonElement) {
        val primitive = raw as? JsonPrimitive
        when (type) {
            SettingType.BOOLEAN -> primitive?.takeUnless { it.isString }?.booleanOrNull?.let { this[booleanPreferencesKey(name)] = it }
            SettingType.INT -> primitive?.takeUnless { it.isString }?.intOrNull?.let { this[intPreferencesKey(name)] = it }
            SettingType.LONG -> primitive?.takeUnless { it.isString }?.longOrNull?.let { this[longPreferencesKey(name)] = it }
            SettingType.FLOAT -> primitive?.takeUnless { it.isString }?.floatOrNull?.let { this[floatPreferencesKey(name)] = it }
            SettingType.DOUBLE -> primitive?.takeUnless { it.isString }?.doubleOrNull?.let { this[doublePreferencesKey(name)] = it }
            SettingType.STRING -> primitive?.takeIf { it.isString }?.let { this[stringPreferencesKey(name)] = it.content }
            SettingType.STRING_SET -> (raw as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.let { this[stringSetPreferencesKey(name)] = it.toSet() }
        }
    }

    private fun encode(type: SettingType, value: Any): JsonElement? {
        val raw: JsonElement = when {
            type == SettingType.BOOLEAN && value is Boolean -> JsonPrimitive(value)
            type == SettingType.INT && value is Int -> JsonPrimitive(value)
            type == SettingType.LONG && value is Long -> JsonPrimitive(value)
            type == SettingType.FLOAT && value is Float -> JsonPrimitive(value)
            type == SettingType.DOUBLE && value is Double -> JsonPrimitive(value)
            type == SettingType.STRING && value is String -> JsonPrimitive(value)
            type == SettingType.STRING_SET && value is Set<*> -> JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
            else -> return null
        }
        return JsonObject(mapOf(type.tag to raw))
    }
}

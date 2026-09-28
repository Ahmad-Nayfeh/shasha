package com.ahmadnayfeh.shasha.policy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The one JSON setup for storing :policy types (Room rows, DataStore, the manual backup).
 *
 * - `encodeDefaults = true`: every field is written, so changing a default in code later never
 *   changes the meaning of profiles and backups saved before.
 * - `ignoreUnknownKeys = true`: an older app version can still read a newer backup.
 *
 * The types check their values when decoded (e.g. a quota above 1440 minutes throws), so decode
 * stored items one at a time ([decodeEach]): one bad entry must not hide all the others.
 */
object PolicyJson {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** One item, or null when [text] is not a valid [T] (bad JSON or a value out of range). */
    fun <T> decodeOrNull(serializer: KSerializer<T>, text: String): T? =
        try {
            json.decodeFromString(serializer, text)
        } catch (_: IllegalArgumentException) { // also SerializationException, a subclass
            null
        }

    /**
     * A JSON array decoded item by item: invalid items are skipped and counted, so the app can say
     * "2 items could not be restored" instead of losing the whole list.
     *
     * @throws SerializationException when [text] is not a JSON array at all.
     */
    fun <T> decodeEach(serializer: KSerializer<T>, text: String): DecodedList<T> {
        val array = json.parseToJsonElement(text) as? JsonArray
            ?: throw SerializationException("Expected a JSON array")
        val items = ArrayList<T>(array.size)
        var skipped = 0
        for (element in array) {
            try {
                items += json.decodeFromJsonElement(serializer, element)
            } catch (_: IllegalArgumentException) { // also SerializationException, a subclass
                skipped++
            }
        }
        return DecodedList(items, skipped)
    }
}

data class DecodedList<T>(val items: List<T>, val skipped: Int)

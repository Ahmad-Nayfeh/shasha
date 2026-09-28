package com.ahmadnayfeh.shasha.remote

import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Turns what the parent uploads (subscriptions.csv itself, or the whole Takeout .zip) into the CSV
 * bytes the controller imports. Google localizes Takeout paths ("YouTube and YouTube Music/
 * subscriptions/subscriptions.csv" in English, Arabic names in an Arabic account), so inside a zip
 * the CSV is found by name first and by content (a UC… channel id at the start of a row) second.
 */
internal object TakeoutFiles {
    const val CSV_NAME = "subscriptions.csv"
    private const val MAX_CSV_BYTES = 5L * 1024 * 1024
    private const val MAX_ENTRIES = 5_000
    private const val MAX_TOTAL_INFLATED = 64L * 1024 * 1024

    private val channelIdRow = Regex("""(?m)^\s*"?UC[0-9A-Za-z_-]{22}"?\s*,""")
    private val nameHints = listOf("subscriptions", "اشتراكات", "الاشتراكات")

    class Csv(val fileName: String, val bytes: ByteArray)

    fun toCsv(fileName: String?, bytes: ByteArray): Csv {
        if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
        val lower = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase().orEmpty()
        return when {
            isZip(bytes) -> fromZip(bytes)
            lower.endsWith(".zip") -> throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, ServerMessages.TAKEOUT_BROKEN)
            lower.isEmpty() || lower.endsWith(".csv") -> Csv(CSV_NAME, bytes)
            else -> throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED, ServerMessages.TAKEOUT_TYPE)
        }
    }

    private fun isZip(bytes: ByteArray) =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    private fun fromZip(bytes: ByteArray): Csv {
        var fallback: ByteArray? = null
        var inflated = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) break
                    val path = entry.name.lowercase()
                    if (entry.isDirectory || !path.endsWith(".csv")) continue
                    val content = readBounded(zip) ?: continue // larger than any subscriptions list
                    inflated += content.size
                    if (inflated > MAX_TOTAL_INFLATED) break
                    if (nameHints.any { it in path.substringAfterLast('/') }) return Csv(CSV_NAME, content)
                    if (fallback == null && channelIdRow.containsMatchIn(content.decodeToString())) fallback = content
                }
            }
        } catch (e: IOException) {
            throw badRequest(ServerMessages.TAKEOUT_BROKEN)
        } catch (e: IllegalArgumentException) { // malformed entry names
            throw badRequest(ServerMessages.TAKEOUT_BROKEN)
        }
        return fallback?.let { Csv(CSV_NAME, it) } ?: throw badRequest(ServerMessages.TAKEOUT_NOT_FOUND)
    }

    /** The current entry's bytes, or null if it inflates past [MAX_CSV_BYTES] (zip bombs stop here). */
    private fun readBounded(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_CSV_BYTES) return null
            out.write(buffer, 0, n)
        }
    }
}

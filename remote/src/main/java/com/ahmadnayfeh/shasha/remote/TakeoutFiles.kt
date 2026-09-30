package com.ahmadnayfeh.shasha.remote

import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    /**
     * Everything inflated from one zip, skipped entries included. Room for a full YouTube Takeout that fits
     * the 20 MB upload (history files inflate ~10x); a zip bomb stops here after a second or two of CPU.
     */
    const val MAX_TOTAL_INFLATED = 256L * 1024 * 1024

    // Blanks may not cross lines: with \s a run of empty lines made this scan quadratic.
    private val channelIdRow = Regex("""(?m)^[ \t\uFEFF]*"?UC[0-9A-Za-z_-]{22}"?[ \t]*,""")
    private val nameHints = listOf("subscriptions", "اشتراكات", "الاشتراكات")

    class Csv(val fileName: String, val bytes: ByteArray)

    fun toCsv(fileName: String?, bytes: ByteArray, maxInflated: Long = MAX_TOTAL_INFLATED): Csv {
        if (bytes.isEmpty()) throw badRequest(ServerMessages.EMPTY_FILE)
        val lower = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase().orEmpty()
        return when {
            isZip(bytes) -> fromZip(bytes, maxInflated)
            lower.endsWith(".zip") -> throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, ServerMessages.TAKEOUT_BROKEN)
            lower.isEmpty() || lower.endsWith(".csv") -> Csv(CSV_NAME, bytes)
            else -> throw ApiException(HttpStatusCode.UnsupportedMediaType, ErrorCodes.UNSUPPORTED, ServerMessages.TAKEOUT_TYPE)
        }
    }

    internal fun hasChannelIdRow(text: String): Boolean = channelIdRow.containsMatchIn(text)

    private fun isZip(bytes: ByteArray) =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    private fun fromZip(bytes: ByteArray, maxInflated: Long): Csv {
        var fallback: ByteArray? = null
        val budget = Budget(maxInflated)
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (!budget.spent) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) break
                    val path = entry.name.lowercase()
                    val csv = !entry.isDirectory && path.endsWith(".csv")
                    // Every entry is read to its end here, counted, even the ones we skip: left to nextEntry,
                    // they would be inflated in full outside any limit.
                    val content = readEntry(zip, keep = if (csv) MAX_CSV_BYTES else 0, budget) ?: continue
                    if (nameHints.any { it in path.substringAfterLast('/') }) return Csv(CSV_NAME, content)
                    if (fallback == null && hasChannelIdRow(content.decodeToString())) fallback = content
                }
            }
        } catch (e: IOException) {
            throw badRequest(ServerMessages.TAKEOUT_BROKEN)
        } catch (e: IllegalArgumentException) { // malformed entry names
            throw badRequest(ServerMessages.TAKEOUT_BROKEN)
        }
        return fallback?.let { Csv(CSV_NAME, it) } ?: throw badRequest(ServerMessages.TAKEOUT_NOT_FOUND)
    }

    private class Budget(var left: Long) {
        val spent get() = left < 0
    }

    /**
     * Reads the current entry to its end (or until [budget] is spent). Returns its bytes if they were
     * kept: at most [keep] of them, so nothing for skipped entries or ones larger than any subscriptions list.
     */
    private fun readEntry(zip: ZipInputStream, keep: Long, budget: Budget): ByteArray? {
        val out = if (keep > 0) ByteArrayOutputStream() else null
        var fits = out != null
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return if (fits) out!!.toByteArray() else null
            budget.left -= n
            if (budget.spent) return null
            if (fits && out!!.size() + n > keep) fits = false
            if (fits) out!!.write(buffer, 0, n)
        }
    }
}

package com.ahmadnayfeh.shasha.data.takeout

/** One channel found in a Takeout export. [name] may be empty (the first feed sync fills it in). */
data class TakeoutChannel(val channelId: String, val name: String)

/**
 * @property channels unique, in file order.
 * @property invalidRows non-blank rows without a channel id (the header row is not counted).
 */
data class TakeoutParseResult(val channels: List<TakeoutChannel>, val invalidRows: Int)

/** The file cannot be read as a subscriptions list at all. */
class TakeoutException(val reason: Reason) : Exception("Takeout file rejected: $reason") {
    enum class Reason {
        EMPTY,

        /** Over [TakeoutCsvParser.MAX_UNCOMPRESSED_BYTES] of CSV, or an archive with too many entries. */
        TOO_LARGE,
        BROKEN_ZIP,

        /** A zip without any .csv file in it. */
        NO_CSV,
    }
}

/**
 * Reads the subscriptions list of a Google Takeout export: `subscriptions.csv` ("Channel Id,Channel
 * Url,Channel Title"), or the whole Takeout .zip.
 *
 * Google localizes the header and the folder names, so nothing depends on them: a data row is one
 * whose first column is a channel id (UC + 22 characters), or any of whose columns holds a
 * `/channel/UC…` link. The name is the row's first other column that is not a link. UTF-8 with or
 * without a BOM (UTF-16 with a BOM too), any line ending, blank lines ignored, duplicates dropped.
 */
object TakeoutCsvParser {
    /** A subscriptions list of 2,000 channels is about 250 KB. */
    const val MAX_UNCOMPRESSED_BYTES = 5 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_NAME_CHARS = 200

    private val channelId = Regex("UC[0-9A-Za-z_-]{22}")
    private val channelLink = Regex("/channel/(UC[0-9A-Za-z_-]{22})(?![0-9A-Za-z_-])")
    private val whitespaceRun = Regex("\\s+")
    private val nameHints = listOf("subscription", "اشتراك")

    /** @throws TakeoutException */
    fun parse(fileName: String?, bytes: ByteArray): TakeoutParseResult {
        if (bytes.isEmpty()) throw TakeoutException(TakeoutException.Reason.EMPTY)
        if (ZipDirectory.looksLikeZip(bytes) || fileName.orEmpty().endsWith(".zip", ignoreCase = true)) return parseZip(bytes)
        if (bytes.size > MAX_UNCOMPRESSED_BYTES) throw TakeoutException(TakeoutException.Reason.TOO_LARGE)
        return parseCsv(decode(bytes))
    }

    fun parseCsv(text: String): TakeoutParseResult {
        val channels = LinkedHashMap<String, String>()
        var invalid = 0
        var firstRow = true
        for (record in CsvReader(text).records()) {
            val fields = record.map(::clean)
            if (fields.all { it.isEmpty() }) continue
            val id = idIn(fields)
            when {
                id != null -> if (channels[id].isNullOrEmpty()) channels[id] = nameIn(fields, id)
                !firstRow -> invalid++
                // else: the header, whatever its language.
            }
            firstRow = false
        }
        return TakeoutParseResult(channels.map { (id, name) -> TakeoutChannel(id, name) }, invalid)
    }

    /**
     * The subscriptions CSV inside a zip: a file named like one first, else the first .csv with channels in
     * it. At most [MAX_UNCOMPRESSED_BYTES] are inflated in total, however large the archive claims to be.
     */
    private fun parseZip(bytes: ByteArray): TakeoutParseResult {
        val zip = try {
            ZipDirectory.open(bytes, MAX_ZIP_ENTRIES)
        } catch (e: ZipFormatException) {
            throw TakeoutException(if (e.tooManyEntries) TakeoutException.Reason.TOO_LARGE else TakeoutException.Reason.BROKEN_ZIP)
        }
        val csvFiles = zip.entries.filter { it.isReadable && it.name.endsWith(".csv", ignoreCase = true) }
        val (named, others) = csvFiles.partition { entry ->
            val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\').lowercase()
            nameHints.any { it in fileName }
        }
        var budget = MAX_UNCOMPRESSED_BYTES
        for (entry in named + others) {
            val content = try {
                zip.read(entry, budget)
            } catch (e: ZipFormatException) {
                throw TakeoutException(TakeoutException.Reason.BROKEN_ZIP)
            } ?: throw TakeoutException(TakeoutException.Reason.TOO_LARGE)
            budget -= content.size
            val result = parseCsv(decode(content))
            // A subscriptions file with no rows is a real answer ("no subscriptions"), not a reason to guess.
            if (entry in named || result.channels.isNotEmpty()) return result
        }
        throw TakeoutException(TakeoutException.Reason.NO_CSV)
    }

    private fun decode(bytes: ByteArray): String {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
        val text = when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            at(0) == 0xFF && at(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            at(0) == 0xFE && at(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.removePrefix("﻿")
    }

    /** Trims blanks, BOMs and invisible direction marks (Arabic spreadsheets add them). */
    private fun clean(field: String): String =
        field.trim { it.isWhitespace() || it == '﻿' || Character.getType(it) == Character.FORMAT.toInt() }

    private fun idIn(fields: List<String>): String? {
        fields.first().takeIf(channelId::matches)?.let { return it }
        return fields.firstNotNullOfOrNull { channelLink.find(it)?.groupValues?.get(1) }
    }

    private fun nameIn(fields: List<String>, id: String): String =
        fields.firstOrNull { it.isNotEmpty() && it != id && !looksLikeLink(it) }
            ?.replace(whitespaceRun, " ")
            ?.take(MAX_NAME_CHARS)
            .orEmpty()

    /** A single token such as "http://www.youtube.com/channel/UC…" (names may contain spaces, links do not). */
    private fun looksLikeLink(s: String) = s.none { it.isWhitespace() } &&
        ("://" in s || s.startsWith("www.", ignoreCase = true) || s.contains("youtube.com/", ignoreCase = true))
}

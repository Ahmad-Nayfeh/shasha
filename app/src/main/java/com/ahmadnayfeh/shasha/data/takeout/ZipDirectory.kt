package com.ahmadnayfeh.shasha.data.takeout

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** [tooManyEntries]: the archive is readable but lists more entries than the caller allows. */
internal class ZipFormatException(message: String, val tooManyEntries: Boolean = false) : Exception(message)

/**
 * Just enough of the ZIP format to take a small file out of an archive held in memory.
 *
 * Entries are listed from the central directory and only the ones asked for are inflated, each with a
 * hard output limit. So a zip bomb costs nothing unless its entry is read, and then stops at the limit
 * (the caller also limits how much compressed input it reads in all: entries may be crafted to overlap).
 * java.util.zip.ZipInputStream cannot skip an entry without inflating all of it. Handles data
 * descriptors and ZIP64 records; encrypted entries and methods other than stored/deflated are skipped.
 */
internal class ZipDirectory private constructor(private val bytes: ByteArray, val entries: List<Entry>) {

    class Entry internal constructor(
        val name: String,
        internal val method: Int,
        internal val compressedSize: Long,
        internal val localHeaderOffset: Long,
        internal val encrypted: Boolean,
    ) {
        val isDirectory: Boolean get() = name.endsWith('/') || name.endsWith('\\')
        val isReadable: Boolean get() = !isDirectory && !encrypted && (method == STORED || method == DEFLATED)
    }

    /** The entry's bytes, or null when they are more than [limit]. */
    fun read(entry: Entry, limit: Int): ByteArray? {
        require(entry.isReadable) { "unreadable entry ${entry.name}" }
        // Bounds are checked as "value > size - offset": ZIP64 values near Long.MAX_VALUE would overflow a sum.
        val header = entry.localHeaderOffset
        if (header < 0 || header > bytes.size - LOCAL_HEADER_SIZE || u32(header) != LOCAL_SIGNATURE) {
            throw ZipFormatException("bad local header for ${entry.name}")
        }
        val dataStart = header + LOCAL_HEADER_SIZE + u16(header + 26) + u16(header + 28)
        if (entry.compressedSize < 0 || dataStart > bytes.size || entry.compressedSize > bytes.size - dataStart) {
            throw ZipFormatException("entry ${entry.name} runs past the end")
        }
        return if (entry.method == STORED) {
            if (entry.compressedSize > limit) null else bytes.copyOfRange(dataStart.toInt(), (dataStart + entry.compressedSize).toInt())
        } else {
            inflate(dataStart.toInt(), entry.compressedSize.toInt(), limit)
        }
    }

    private fun inflate(start: Int, length: Int, limit: Int): ByteArray? {
        val inflater = Inflater(/* nowrap = */ true)
        try {
            inflater.setInput(bytes, start, length)
            val out = ByteArrayOutputStream(minOf(limit, 64 * 1024).coerceAtLeast(0))
            val buffer = ByteArray(16 * 1024)
            var paddedInput = false
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    when {
                        inflater.finished() -> break
                        inflater.needsDictionary() -> throw ZipFormatException("deflate stream needs a dictionary")
                        // zlib may want one extra byte after raw deflate data (java.util.zip.ZipFile adds it too).
                        inflater.needsInput() && !paddedInput -> {
                            inflater.setInput(ByteArray(1))
                            paddedInput = true
                        }
                        else -> throw ZipFormatException("truncated deflate stream")
                    }
                    continue
                }
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw ZipFormatException("corrupt deflate stream: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun u16(at: Long): Int = u16(bytes, at)

    private fun u32(at: Long): Long = u32(bytes, at)

    companion object {
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val LOCAL_SIGNATURE = 0x04034b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val END_SIGNATURE = 0x06054b50L
        private const val ZIP64_END_SIGNATURE = 0x06064b50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val MAX_COMMENT = 0xFFFF
        private const val U32_MAX = 0xFFFFFFFFL

        /** "PK\u0003\u0004" (an archive with entries) or "PK\u0005\u0006" (an empty one). */
        fun looksLikeZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && (u32(bytes, 0) == LOCAL_SIGNATURE || u32(bytes, 0) == END_SIGNATURE)

        /**
         * @throws ZipFormatException when the archive is broken or lists more than [maxEntries] entries.
         */
        fun open(bytes: ByteArray, maxEntries: Int): ZipDirectory {
            val end = findEnd(bytes) ?: throw ZipFormatException("no end of central directory")
            var count = u16(bytes, end + 10).toLong()
            var directorySize = u32(bytes, end + 12)
            var directoryOffset = u32(bytes, end + 16)
            val locator = end - ZIP64_LOCATOR_SIZE
            if (locator >= 0 && u32(bytes, locator) == ZIP64_LOCATOR_SIGNATURE) {
                val zip64End = u64(bytes, locator + 8)
                if (zip64End < 0 || zip64End > bytes.size - 56 || u32(bytes, zip64End) != ZIP64_END_SIGNATURE) {
                    throw ZipFormatException("bad ZIP64 end record")
                }
                count = u64(bytes, zip64End + 32)
                directorySize = u64(bytes, zip64End + 40)
                directoryOffset = u64(bytes, zip64End + 48)
            }
            if (count > maxEntries) throw ZipFormatException("too many entries: $count", tooManyEntries = true)
            if (count < 0) throw ZipFormatException("bad entry count")
            if (directoryOffset < 0 || directorySize < 0 || directoryOffset > bytes.size || directorySize > bytes.size - directoryOffset) {
                throw ZipFormatException("central directory out of bounds")
            }
            val entries = ArrayList<Entry>(count.toInt())
            var at = directoryOffset
            repeat(count.toInt()) {
                if (at + CENTRAL_HEADER_SIZE > bytes.size || u32(bytes, at) != CENTRAL_SIGNATURE) {
                    throw ZipFormatException("bad central directory header")
                }
                val nameLength = u16(bytes, at + 28)
                val extraLength = u16(bytes, at + 30)
                val commentLength = u16(bytes, at + 32)
                val nameStart = at + CENTRAL_HEADER_SIZE
                val next = nameStart + nameLength + extraLength + commentLength
                if (next > bytes.size) throw ZipFormatException("central directory header out of bounds")
                val sizes = Zip64Sizes(
                    uncompressed = u32(bytes, at + 24),
                    compressed = u32(bytes, at + 20),
                    offset = u32(bytes, at + 42),
                ).withExtra(bytes, nameStart + nameLength, extraLength)
                entries += Entry(
                    name = String(bytes, nameStart.toInt(), nameLength, Charsets.UTF_8),
                    method = u16(bytes, at + 10),
                    compressedSize = sizes.compressed,
                    localHeaderOffset = sizes.offset,
                    encrypted = u16(bytes, at + 8) and 1 != 0,
                )
                at = next
            }
            return ZipDirectory(bytes, entries)
        }

        private fun findEnd(bytes: ByteArray): Long? {
            val lowest = maxOf(0, bytes.size - END_SIZE - MAX_COMMENT)
            var at = bytes.size - END_SIZE
            while (at >= lowest) {
                if (u32(bytes, at.toLong()) == END_SIGNATURE && at + END_SIZE + u16(bytes, at + 20L) <= bytes.size) return at.toLong()
                at--
            }
            return null
        }

        /** The 32-bit fields of a central header; U32_MAX means "the value is in the ZIP64 extra field". */
        private data class Zip64Sizes(val uncompressed: Long, val compressed: Long, val offset: Long) {
            fun withExtra(bytes: ByteArray, extraStart: Long, extraLength: Int): Zip64Sizes {
                if (uncompressed != U32_MAX && compressed != U32_MAX && offset != U32_MAX) return this
                var at = extraStart
                val end = extraStart + extraLength
                while (at + 4 <= end) {
                    val id = u16(bytes, at)
                    val size = u16(bytes, at + 2)
                    if (id == 0x0001) {
                        var field = at + 4
                        val fieldsEnd = minOf(field + size, end)
                        fun next(value: Long): Long =
                            if (value != U32_MAX || field + 8 > fieldsEnd) value else u64(bytes, field).also { field += 8 }
                        val u = next(uncompressed)
                        val c = next(compressed)
                        return Zip64Sizes(u, c, next(offset))
                    }
                    at += 4 + size
                }
                return this
            }
        }

        private fun u16(b: ByteArray, at: Long): Int {
            val i = at.toInt()
            return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32(b: ByteArray, at: Long): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

        private fun u32(b: ByteArray, at: Int): Long = u32(b, at.toLong())

        /** Values above Long.MAX_VALUE come back negative, which every caller rejects. */
        private fun u64(b: ByteArray, at: Long): Long = u32(b, at) or (u32(b, at + 4) shl 32)
    }
}

package com.ahmadnayfeh.shasha.data.takeout

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutCsvParserTest {
    private val a = "UC1JTQBa5QxZCpXrFSkMxmPw"
    private val b = "UCFl7yKfcRcFmIUbKeCA-SJQ"
    private val c = "UCsXVk37bltHxD1rDPwtNM8Q"
    private val header = "Channel Id,Channel Url,Channel Title"

    private fun row(id: String, title: String) = "$id,http://www.youtube.com/channel/$id,$title"

    private fun parse(text: String) = TakeoutCsvParser.parse("subscriptions.csv", text.toByteArray())

    private fun ids(result: TakeoutParseResult) = result.channels.map { it.channelId }

    private fun reason(block: () -> Unit) = assertThrows(TakeoutException::class.java) { block() }.reason

    // ---- CSV ------------------------------------------------------------------------------------

    @Test
    fun standardTakeoutExport() {
        val result = parse("$header\n${row(a, "Raycevick")}\n${row(b, "Joji")}\n")
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "Raycevick"), TakeoutChannel(b, "Joji")).inOrder()
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun quotedTitleWithCommas() {
        val result = parse("$header\n$a,http://www.youtube.com/channel/$a,\"Crash Course, Kids, and more\"\n")
        assertThat(result.channels.single().name).isEqualTo("Crash Course, Kids, and more")
    }

    @Test
    fun escapedQuotesInsideAQuotedTitle() {
        val result = parse("$header\n$a,http://www.youtube.com/channel/$a,\"The \"\"Best\"\" Channel\"\n")
        assertThat(result.channels.single().name).isEqualTo("The \"Best\" Channel")
    }

    @Test
    fun lineBreakInsideAQuotedTitle_staysOneRow() {
        val result = parse("$header\n$a,http://www.youtube.com/channel/$a,\"Line one\nline two\"\n${row(b, "Next")}\n")
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "Line one line two"), TakeoutChannel(b, "Next")).inOrder()
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun utf8ByteOrderMark_andArabicTitles() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "$header\n${row(a, "قناة الجزيرة")}\n".toByteArray()
        val result = TakeoutCsvParser.parse("subscriptions.csv", bytes)
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "قناة الجزيرة"))
        assertThat(result.invalidRows).isEqualTo(0) // the BOM did not turn the header into an invalid row
    }

    @Test
    fun byteOrderMarkBeforeAChannelIdWithoutHeader() {
        val result = TakeoutCsvParser.parse(null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "${row(a, "x")}\n".toByteArray())
        assertThat(ids(result)).containsExactly(a)
    }

    @Test
    fun utf16WithByteOrderMark() {
        val result = TakeoutCsvParser.parse("subscriptions.csv", "﻿$header\r\n${row(a, "طيور الجنة")}\r\n".toByteArray(Charsets.UTF_16LE))
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "طيور الجنة"))
    }

    @Test
    fun crlfAndLoneCrLineEndings() {
        assertThat(ids(parse("$header\r\n${row(a, "A")}\r\n${row(b, "B")}\r\n"))).containsExactly(a, b).inOrder()
        assertThat(ids(parse("$header\r${row(a, "A")}\r${row(b, "B")}"))).containsExactly(a, b).inOrder()
    }

    @Test
    fun localizedHeader_isRecognizedByHavingNoChannelId() {
        val result = parse("معرّف القناة,عنوان URL للقناة,عنوان القناة\n${row(a, "سبيستون")}\n")
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "سبيستون"))
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun noHeaderAtAll() {
        val result = parse("${row(a, "A")}\n${row(b, "B")}")
        assertThat(ids(result)).containsExactly(a, b).inOrder()
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun urlOnlyRows_inAnyColumnAndForm() {
        val text = """
            Channel Url
            https://www.youtube.com/channel/$a
            http://youtube.com/channel/$b/videos?view=0
            "Kurzgesagt",www.youtube.com/channel/$c
        """.trimIndent()
        val result = parse(text)
        assertThat(result.channels)
            .containsExactly(TakeoutChannel(a, ""), TakeoutChannel(b, ""), TakeoutChannel(c, "Kurzgesagt")).inOrder()
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun blankLinesAndPaddingAreIgnored() {
        val result = parse("\n\n$header\n\n  $a , http://www.youtube.com/channel/$a ,  Padded Name  \n,,\n\r\n${row(b, "B")}\n\n")
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "Padded Name"), TakeoutChannel(b, "B")).inOrder()
        assertThat(result.invalidRows).isEqualTo(0)
    }

    @Test
    fun duplicatesAreDropped_aMissingNameIsFilledByALaterRow() {
        val result = parse("$header\n${row(a, "First")}\n${row(a, "Second")}\n$b,,\n${row(b, "Named later")}\n")
        assertThat(result.channels).containsExactly(TakeoutChannel(a, "First"), TakeoutChannel(b, "Named later")).inOrder()
    }

    @Test
    fun rowsWithoutAValidId_areCountedAsInvalid_butNotTheHeader() {
        val text = listOf(
            header,
            row(a, "Good"),
            "UC123,http://www.youtube.com/channel/UC123,Too short",
            "uc1JTQBa5QxZCpXrFSkMxmPw,,lower-case prefix",
            "${a}X,,one character too many",
            "just some text",
            row(b, "Good too"),
        ).joinToString("\n")
        val result = parse(text)
        assertThat(ids(result)).containsExactly(a, b).inOrder()
        assertThat(result.invalidRows).isEqualTo(4)
    }

    @Test
    fun anUnterminatedQuote_runsToTheEndWithoutFailing() {
        val result = parse("$header\n${row(a, "A")}\n$b,http://www.youtube.com/channel/$b,\"Never closed\n${row(c, "C")}")
        assertThat(ids(result)).containsExactly(a, b).inOrder()
        assertThat(result.channels[1].name).startsWith("Never closed")
    }

    @Test
    fun hugeFieldsAndRows_areCappedNotBuffered() {
        val manyFields = "x,".repeat(200_000)
        val longName = "y".repeat(1_000_000)
        val result = parse("$header\n$manyFields\n$a,,\"$longName\"\n")
        assertThat(ids(result)).containsExactly(a)
        assertThat(result.channels.single().name.length).isAtMost(200)
        assertThat(result.invalidRows).isEqualTo(1)
    }

    @Test
    fun emptyAndOversizedInput_areRejected() {
        assertThat(reason { TakeoutCsvParser.parse("subscriptions.csv", ByteArray(0)) }).isEqualTo(TakeoutException.Reason.EMPTY)
        val big = ByteArray(TakeoutCsvParser.MAX_UNCOMPRESSED_BYTES + 1) { 'a'.code.toByte() }
        assertThat(reason { TakeoutCsvParser.parse("subscriptions.csv", big) }).isEqualTo(TakeoutException.Reason.TOO_LARGE)
    }

    @Test
    fun aFileWithoutChannels_parsesToNothing() {
        val result = parse("Video ID,Playlist Video Creation Timestamp\ndQw4w9WgXcQ,2024-01-01T00:00:00+00:00\n")
        assertThat(result.channels).isEmpty()
        assertThat(result.invalidRows).isEqualTo(1)
    }

    // ---- Zip ------------------------------------------------------------------------------------

    private val csv = "$header\n${row(a, "A")}\n${row(b, "B")}\n"

    @Test
    fun zip_findsTheSubscriptionsFileAmongTakeoutFolders() {
        val zip = zipOf(
            "Takeout/archive_browser.html" to "<html></html>".toByteArray(),
            "Takeout/YouTube and YouTube Music/history/watch-history.json" to "[]".toByteArray(),
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csv.toByteArray(),
        )
        assertThat(ids(TakeoutCsvParser.parse("takeout-20260928T000000Z-001.zip", zip))).containsExactly(a, b).inOrder()
    }

    @Test
    fun zip_prefersTheSubscriptionsFile_overOtherCsvsWithChannelIds() {
        // Takeout's channels/channel.csv starts with the owner's own channel id.
        val zip = zipOf(
            "Takeout/YouTube/channels/channel.csv" to "Channel ID,Channel Title (Original)\n$c,My channel\n".toByteArray(),
            "Takeout/YouTube/subscriptions/subscriptions.csv" to csv.toByteArray(),
        )
        assertThat(ids(TakeoutCsvParser.parse("t.zip", zip))).containsExactly(a, b).inOrder()
    }

    @Test
    fun zip_localizedNames_andAnyCsvWithChannelsAsFallback() {
        val arabic = zipOf(
            "Takeout/يوتيوب وYouTube Music/الاشتراكات/الاشتراكات.csv" to csv.toByteArray(),
        )
        assertThat(ids(TakeoutCsvParser.parse("تصدير.zip", arabic))).containsExactly(a, b).inOrder()

        val unnamed = zipOf(
            "Takeout/YouTube/playlists/Watch later.csv" to "Video ID,Timestamp\ndQw4w9WgXcQ,2024\n".toByteArray(),
            "Takeout/YouTube/data.csv" to csv.toByteArray(),
        )
        assertThat(ids(TakeoutCsvParser.parse("t.zip", unnamed))).containsExactly(a, b).inOrder()
    }

    @Test
    fun zip_storedEntries() {
        val zip = zipOf("subscriptions.csv" to csv.toByteArray(), stored = true)
        assertThat(ids(TakeoutCsvParser.parse(null, zip))).containsExactly(a, b).inOrder()
    }

    @Test
    fun zip_entriesThatAreNotChosen_areNeverInflated() {
        // A deflated entry with a corrupted stream: inflating it would fail. It is not a CSV, so it is skipped
        // without being touched, however large or broken it is.
        val zip = zipOf(
            "Takeout/YouTube/videos/huge.bin" to ByteArray(8 * 1024 * 1024),
            "Takeout/YouTube/subscriptions/subscriptions.csv" to csv.toByteArray(),
        )
        val dataStart = 30 + "Takeout/YouTube/videos/huge.bin".length
        for (i in dataStart + 4 until dataStart + 64) zip[i] = 0xFF.toByte()
        assertThat(ids(TakeoutCsvParser.parse("t.zip", zip))).containsExactly(a, b).inOrder()
    }

    @Test(timeout = 20_000)
    fun zipBomb_stopsAtTheUncompressedCap() {
        // 64 MB of blanks deflates to ~64 KB; only 5 MB of it is ever inflated.
        val bomb = zipOf("Takeout/YouTube/subscriptions/subscriptions.csv" to ByteArray(64 * 1024 * 1024) { ' '.code.toByte() })
        assertThat(bomb.size).isLessThan(1024 * 1024)
        assertThat(reason { TakeoutCsvParser.parse("t.zip", bomb) }).isEqualTo(TakeoutException.Reason.TOO_LARGE)
    }

    @Test
    fun zip_capCountsAllEntriesInflated_notEachOne() {
        val threeMb = ByteArray(3 * 1024 * 1024) { '\n'.code.toByte() }
        val zip = zipOf("a/first.csv" to threeMb, "b/second.csv" to threeMb, "c/data.csv" to csv.toByteArray())
        assertThat(reason { TakeoutCsvParser.parse("t.zip", zip) }).isEqualTo(TakeoutException.Reason.TOO_LARGE)
    }

    @Test
    fun zip_withTooManyEntries_isRejected() {
        val entries = (0..TakeoutCsvParser.MAX_ZIP_ENTRIES).map { "f$it.txt" to ByteArray(0) }.toTypedArray()
        assertThat(reason { TakeoutCsvParser.parse("t.zip", zipOf(*entries)) }).isEqualTo(TakeoutException.Reason.TOO_LARGE)
    }

    @Test
    fun zip_brokenOrWithoutCsv_isRejected() {
        val zip = zipOf("Takeout/YouTube/subscriptions/subscriptions.csv" to csv.toByteArray())
        assertThat(reason { TakeoutCsvParser.parse("t.zip", zip.copyOf(zip.size - 30)) }).isEqualTo(TakeoutException.Reason.BROKEN_ZIP)
        assertThat(reason { TakeoutCsvParser.parse("t.zip", "not a zip at all".toByteArray()) }).isEqualTo(TakeoutException.Reason.BROKEN_ZIP)
        val noCsv = zipOf("Takeout/archive_browser.html" to "<html></html>".toByteArray())
        assertThat(reason { TakeoutCsvParser.parse("t.zip", noCsv) }).isEqualTo(TakeoutException.Reason.NO_CSV)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

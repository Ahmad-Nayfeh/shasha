package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutFilesTest {
    private val id = "UC1234567890123456789012"
    private val csv = "Channel Id,Channel Url,Channel Title\n$id,http://www.youtube.com/channel/$id,قناة\n"

    @Test
    fun aCsvWithAnUnknownName_isFoundByItsContent() {
        val zip = zipOf("Takeout/archive_browser.html" to "<html></html>".toByteArray(), "Takeout/YouTube/data/file.csv" to csv.toByteArray())
        val found = TakeoutFiles.toCsv("takeout.zip", zip)
        assertThat(found.fileName).isEqualTo(TakeoutFiles.CSV_NAME)
        assertThat(found.bytes.decodeToString()).isEqualTo(csv)
    }

    @Test
    fun channelIdRows_quotedOrIndented_butOnlyAtTheStartOfARow() {
        assertThat(TakeoutFiles.hasChannelIdRow(csv)).isTrue()
        assertThat(TakeoutFiles.hasChannelIdRow("x\r\n\"$id\",url,t")).isTrue()
        assertThat(TakeoutFiles.hasChannelIdRow("x\n \t$id ,url,t")).isTrue()
        assertThat(TakeoutFiles.hasChannelIdRow("\uFEFF$id,url,t")).isTrue()
        assertThat(TakeoutFiles.hasChannelIdRow("name,$id,url")).isFalse()
        assertThat(TakeoutFiles.hasChannelIdRow("${id}X,url")).isFalse()
        assertThat(TakeoutFiles.hasChannelIdRow("\n\n$id\n,url")).isFalse() // the comma must be on the same row
    }

    @Test(timeout = 10_000)
    fun blankRuns_areScannedInLinearTime() {
        // With \s in the pattern these took hours (every line start re-scanned the rest of the run).
        assertThat(TakeoutFiles.hasChannelIdRow("\n".repeat(5_000_000))).isFalse()
        assertThat(TakeoutFiles.hasChannelIdRow("\r\n \t".repeat(1_000_000))).isFalse()
        assertThat(TakeoutFiles.hasChannelIdRow(" ".repeat(2_000_000) + "\n" + "\t".repeat(2_000_000))).isFalse()
        val zip = zipOf("Takeout/YouTube/history/data.csv" to ByteArray(5_000_000) { '\n'.code.toByte() })
        val error = assertThrows(ApiException::class.java) { TakeoutFiles.toCsv("t.zip", zip) }
        assertThat(error.message).isEqualTo(ServerMessages.TAKEOUT_NOT_FOUND)
    }

    @Test
    fun skippedEntries_countTowardTheInflationBudget() {
        val zip = zipOf(
            "Takeout/YouTube/videos/big.mp4" to ByteArray(2 * 1024 * 1024),
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csv.toByteArray(),
        )
        assertThat(TakeoutFiles.toCsv("t.zip", zip).bytes.decodeToString()).isEqualTo(csv)
        // A budget smaller than the skipped video: the scan stops before it reaches the CSV.
        val error = assertThrows(ApiException::class.java) { TakeoutFiles.toCsv("t.zip", zip, maxInflated = 1024 * 1024) }
        assertThat(error.message).isEqualTo(ServerMessages.TAKEOUT_NOT_FOUND)
    }

    @Test
    fun aCsvLargerThanAnySubscriptionList_isSkipped() {
        val zip = zipOf(
            "Takeout/YouTube/subscriptions-old.csv" to ByteArray(6 * 1024 * 1024) { 'a'.code.toByte() },
            "Takeout/YouTube/data.csv" to csv.toByteArray(),
        )
        assertThat(TakeoutFiles.toCsv("t.zip", zip).bytes.decodeToString()).isEqualTo(csv)
    }

    @Test
    fun aBrokenZip_isABadRequest() {
        val zip = zipOf("Takeout/x/subscriptions.csv" to csv.toByteArray())
        val error = assertThrows(ApiException::class.java) { TakeoutFiles.toCsv("t.zip", zip.copyOf(60)) }
        assertThat(error.message).isAnyOf(ServerMessages.TAKEOUT_BROKEN, ServerMessages.TAKEOUT_NOT_FOUND)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

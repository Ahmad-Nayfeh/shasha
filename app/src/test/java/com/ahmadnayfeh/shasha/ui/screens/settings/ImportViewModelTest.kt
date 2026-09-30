package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ImportResult
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/** «استيراد الاشتراكات»: the ADB drop file is picked up by itself; a picked file is imported; failures say why. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: ShashaDatabase
    private lateinit var subscriptions: SubscriptionRepository
    private val csv = "Channel Id,Channel Url,Channel Title\n" +
        "${channelId(1)},http://www.youtube.com/channel/${channelId(1)},قناة أولى\n" +
        "${channelId(2)},http://www.youtube.com/channel/${channelId(2)},Second Channel\n"

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        subscriptions = SubscriptionRepository(db, FakeUiSource())
    }

    @After
    fun tearDown() = db.close()

    private fun vm(drop: File?) = ImportViewModel(subscriptions, drop, io = Dispatchers.IO, pollMs = 20)

    @Test
    fun theAdbCommandNamesTheRealFolder() {
        val vm = vm(File("/storage/emulated/0/Android/data/com.ahmadnayfeh.shasha/files/import/subscriptions.csv"))
        assertThat(vm.adbCommand).isEqualTo(
            "adb push subscriptions.csv \\\n    /sdcard/Android/data/com.ahmadnayfeh.shasha/files/import/subscriptions.csv",
        )
        assertThat(vm(null).adbCommand).isNull()
    }

    @Test
    fun aFilePushedWithAdbIsImportedAndRemoved(): Unit = runBlocking {
        val drop = File(folder.root, "import/subscriptions.csv")
        val vm = vm(drop)
        val watching = async(Dispatchers.Default) { vm.watchDropFile() }
        drop.parentFile!!.mkdirs()
        drop.writeText(csv)
        val done = withTimeout(10_000) { vm.status.first { it is ImportStatus.Done } }
        watching.cancel()
        assertThat((done as ImportStatus.Done).result).isEqualTo(ImportResult(added = 2, alreadyPresent = 0, invalid = 0))
        assertThat(drop.exists()).isFalse()
        assertThat(subscriptions.observe().first().map { it.name }).containsExactly("قناة أولى", "Second Channel")
    }

    @Test
    fun aPickedFileIsImported(): Unit = runBlocking {
        val vm = vm(null)
        vm.importPicked { "subscriptions.csv" to csv.byteInputStream() }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Done(ImportResult(added = 2, alreadyPresent = 0, invalid = 0)))
        vm.importPicked { "subscriptions.csv" to csv.byteInputStream() }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Done(ImportResult(added = 0, alreadyPresent = 2, invalid = 0)))
    }

    @Test
    fun failuresSayWhy(): Unit = runBlocking {
        val vm = vm(null)
        vm.importPicked { "subscriptions.csv" to "Channel Id,Channel Url,Channel Title\n".byteInputStream() }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Failed(ImportFailure.NO_CHANNELS))
        vm.importPicked { "empty.csv" to ByteArray(0).inputStream() }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Failed(ImportFailure.EMPTY))
        vm.importPicked { "huge.zip" to endless() }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Failed(ImportFailure.TOO_LARGE))
        vm.importPicked { "gone.csv" to null }
        assertThat(vm.status.value).isEqualTo(ImportStatus.Failed(ImportFailure.UNREADABLE))
        vm.pickerUnavailable()
        assertThat(vm.status.value).isEqualTo(ImportStatus.Failed(ImportFailure.NO_PICKER))
    }

    /** A stream that never ends (without holding it in memory). */
    private fun endless() = object : InputStream() {
        override fun read(): Int = 'x'.code
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill('x'.code.toByte(), off, off + len)
            return len
        }
    }
}

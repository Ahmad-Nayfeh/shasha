package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.ImportResult
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.takeout.TakeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@Immutable
sealed interface ImportStatus {
    /** Nothing imported yet; the ADB drop file is being watched. */
    data object Waiting : ImportStatus

    data object Importing : ImportStatus

    data class Done(val result: ImportResult) : ImportStatus

    data class Failed(val reason: ImportFailure) : ImportStatus
}

enum class ImportFailure { EMPTY, TOO_LARGE, BROKEN_ZIP, NO_CSV, NO_CHANNELS, UNREADABLE, NO_PICKER }

/**
 * «استيراد الاشتراكات» from a file on the TV: picked with the system file picker when the TV has one,
 * or pushed from a computer with ADB to [dropFile] (in the app's own external files folder: no storage
 * permission needed). While the screen is open the drop file is checked every [pollMs]; it is deleted
 * once read, so the same file is never imported twice.
 */
class ImportViewModel(
    private val subscriptions: SubscriptionRepository,
    val dropFile: File?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMs: Long = 1_500L,
) : ViewModel() {

    private val _status = MutableStateFlow<ImportStatus>(ImportStatus.Waiting)
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    /** The exact command for the drop file's real path ("/sdcard/…" is the same folder, shorter to type). */
    val adbCommand: String? = dropFile?.let { "adb push subscriptions.csv \\\n    " + it.absolutePath.replace(EMULATED_STORAGE, "/sdcard") }

    /** Watches the drop file until cancelled; run it only while the screen is visible. */
    suspend fun watchDropFile() {
        val file = dropFile ?: return
        withContext(io) { file.parentFile?.mkdirs() }
        var lastSize = -1L
        while (true) {
            val size = withContext(io) { if (file.isFile) file.length() else -1L }
            // Import once the size stops changing: adb may still be writing the file.
            if (size > 0 && size == lastSize) {
                val bytes = withContext(io) {
                    try {
                        runCatching { readCapped(file.inputStream(), file.length()) }
                    } finally {
                        file.delete()
                    }
                }
                importNow({ file.name }) { bytes.getOrThrow() }
                lastSize = -1L
            } else {
                lastSize = size
            }
            delay(pollMs)
        }
    }

    /** A file chosen with the system picker: [open] runs on the IO dispatcher and gives its name and content. */
    fun onPicked(open: () -> Pair<String, InputStream?>) {
        viewModelScope.launch { importPicked(open) }
    }

    suspend fun importPicked(open: () -> Pair<String, InputStream?>) {
        var name = PICKED_NAME
        importNow({ name }) {
            withContext(io) {
                val (fileName, stream) = open()
                name = fileName
                stream?.let { readCapped(it, sizeHint = -1) } ?: throw IOException("Cannot open $fileName")
            }
        }
    }

    /** The TV has no app that answers ACTION_OPEN_DOCUMENT. */
    fun pickerUnavailable() {
        _status.value = ImportStatus.Failed(ImportFailure.NO_PICKER)
    }

    private suspend fun importNow(name: () -> String, read: suspend () -> ByteArray) {
        _status.value = ImportStatus.Importing
        _status.value = try {
            val bytes = read()
            val result = subscriptions.importTakeout(name(), bytes)
            if (result.added + result.alreadyPresent == 0) ImportStatus.Failed(ImportFailure.NO_CHANNELS) else ImportStatus.Done(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TakeoutException) {
            ImportStatus.Failed(e.reason.toFailure())
        } catch (e: TooLargeException) {
            ImportStatus.Failed(ImportFailure.TOO_LARGE)
        } catch (e: IOException) {
            ImportStatus.Failed(ImportFailure.UNREADABLE)
        } catch (e: SecurityException) {
            ImportStatus.Failed(ImportFailure.UNREADABLE)
        }
    }

    private class TooLargeException : IOException()

    companion object {
        /** A whole Takeout zip may be large; the CSV inside is small (the parser caps it separately). */
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
        private const val EMULATED_STORAGE = "/storage/emulated/0"
        private const val PICKED_NAME = "subscriptions.csv"

        private fun TakeoutException.Reason.toFailure() = when (this) {
            TakeoutException.Reason.EMPTY -> ImportFailure.EMPTY
            TakeoutException.Reason.TOO_LARGE -> ImportFailure.TOO_LARGE
            TakeoutException.Reason.BROKEN_ZIP -> ImportFailure.BROKEN_ZIP
            TakeoutException.Reason.NO_CSV -> ImportFailure.NO_CSV
        }

        /** Reads at most [MAX_FILE_BYTES] (InputStream.readNBytes needs API 33). */
        private fun readCapped(input: InputStream, sizeHint: Long): ByteArray = input.use { stream ->
            if (sizeHint > MAX_FILE_BYTES) throw TooLargeException()
            val out = ByteArrayOutputStream(if (sizeHint in 1..MAX_FILE_BYTES) sizeHint.toInt() else 64 * 1024)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw TooLargeException()
            }
            out.toByteArray()
        }
    }
}

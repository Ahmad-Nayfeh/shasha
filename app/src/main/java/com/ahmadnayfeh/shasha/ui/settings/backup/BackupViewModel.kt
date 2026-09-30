package com.ahmadnayfeh.shasha.ui.settings.backup

import android.annotation.SuppressLint
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.backup.BackupException
import com.ahmadnayfeh.shasha.backup.BackupFile
import com.ahmadnayfeh.shasha.backup.BackupManager
import com.ahmadnayfeh.shasha.backup.BackupProblem
import com.ahmadnayfeh.shasha.backup.RestoreReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

@Immutable
sealed interface BackupStatus {
    data object Idle : BackupStatus

    data object Working : BackupStatus

    data class Exported(val fileName: String) : BackupStatus

    data class Restored(val report: RestoreReport) : BackupStatus

    /** [problem] null: the file could not be written or read. */
    data class Failed(val problem: BackupProblem?, val export: Boolean) : BackupStatus
}

/**
 * «الاستيراد والتصدير» on the TV: exports to the app's own folder (`adb pull` takes it from there, no storage
 * permission needed) and restores a file found there (put with `adb push`, or an earlier export). The folder
 * is looked at every [pollMs] while the screen is shown. The work runs on [appScope]: a restore half done
 * must not stop because the screen closed.
 */
class BackupViewModel(
    private val backup: BackupManager,
    private val appScope: CoroutineScope,
    private val pollMs: Long = 2_000L,
) : ViewModel() {
    private val _files = MutableStateFlow<List<BackupFile>>(emptyList())
    val files: StateFlow<List<BackupFile>> = _files.asStateFlow()

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    val folder: File? get() = backup.backupDir

    /** `adb pull <file>` for the latest export (or the folder), with the short "/sdcard" path. */
    fun pullCommand(): String? {
        val target = (status.value as? BackupStatus.Exported)?.fileName?.let { name -> folder?.let { File(it, name) } } ?: folder ?: return null
        return "adb pull " + target.shortPath()
    }

    fun pushCommand(): String? = folder?.let { "adb push shasha-backup.json " + it.shortPath() + "/" }

    /** Follows the folder until cancelled; run it only while the screen is visible. */
    suspend fun watchFiles() {
        while (true) {
            _files.value = runCatching { backup.listFiles() }.getOrDefault(emptyList())
            delay(pollMs)
        }
    }

    fun export() = work(export = true) {
        val file = backup.exportToFile()
        _files.value = runCatching { backup.listFiles() }.getOrDefault(_files.value)
        BackupStatus.Exported(file.name)
    }

    fun restore(file: BackupFile) = work(export = false) { BackupStatus.Restored(backup.importFile(file.file)) }

    private fun work(export: Boolean, block: suspend () -> BackupStatus) {
        if (_status.value == BackupStatus.Working) return
        _status.value = BackupStatus.Working
        appScope.launch {
            _status.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                BackupStatus.Failed(e.problem, export)
            } catch (e: Exception) {
                BackupStatus.Failed(null, export)
            }
        }
    }

    /** The path as typed in an adb command on the computer: adb's shell knows /sdcard, not the app's view of it. */
    @SuppressLint("SdCardPath")
    private fun File.shortPath(): String = absolutePath.replace(EMULATED_STORAGE, "/sdcard")

    private companion object {
        const val EMULATED_STORAGE = "/storage/emulated/0"
    }
}

package com.ahmadnayfeh.shasha.mobile.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.update.InstallEvent
import com.ahmadnayfeh.shasha.mobile.update.InstalledVersion
import com.ahmadnayfeh.shasha.mobile.update.ReleaseInfo
import com.ahmadnayfeh.shasha.mobile.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.mobile.update.UpdateException
import com.ahmadnayfeh.shasha.mobile.update.UpdateSteps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

/** Where the update page is. The page has one button: what it does depends on this. */
sealed interface UpdateUi {
    data object Checking : UpdateUi

    data class UpToDate(val installed: InstalledVersion) : UpdateUi

    data class Available(val installed: InstalledVersion, val release: ReleaseInfo) : UpdateUi

    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long?) : UpdateUi

    /** The file is ready, but the phone has not been told to allow installs from this app yet. */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateUi

    data class Installing(val release: ReleaseInfo) : UpdateUi

    /** [release] set: «try again» continues with it. */
    data class Failed(val kind: UpdateErrorKind, val detail: String?, val release: ReleaseInfo?) : UpdateUi
}

/**
 * The update page: looks for a newer release when it opens, then offers one button. «Download and install» downloads
 * the file, checks it ([com.ahmadnayfeh.shasha.mobile.update.ApkVerifier]), makes sure the phone allows installs from
 * this app, and hands it to the system installer. The system's confirmation (when it asks) goes to [confirmations].
 */
class UpdateViewModel(private val steps: UpdateSteps) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Checking)
    val state: StateFlow<UpdateUi> = _state

    private val confirmationChannel = Channel<Intent>(Channel.BUFFERED)

    /** The system installer's dialog, to open from the screen. */
    val confirmations: Flow<Intent> = confirmationChannel.receiveAsFlow()

    private var work: Job? = null
    private var apk: File? = null
    private var session: Int? = null

    init {
        check()
        viewModelScope.launch {
            steps.installEvents.collect { event ->
                if (event.sessionId != session && session != null) return@collect
                when (event) {
                    is InstallEvent.Confirm -> confirmationChannel.send(event.intent)
                    is InstallEvent.Installed -> Unit // the app is replaced; nothing left to show
                    is InstallEvent.Cancelled -> backToAvailable()
                    is InstallEvent.Failed -> _state.value = failed(UpdateErrorKind.INSTALL_FAILED, event.message, currentRelease())
                }
            }
        }
    }

    /** Looks for a newer release. */
    fun check() {
        work?.cancel()
        _state.value = UpdateUi.Checking
        work = viewModelScope.launch {
            try {
                val release = steps.latest()
                _state.value = if (release.isNewerThan(steps.installed.code)) {
                    UpdateUi.Available(steps.installed, release)
                } else {
                    UpdateUi.UpToDate(steps.installed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                _state.value = failed(e.kind, e.detail, null)
            } catch (e: Exception) {
                _state.value = failed(UpdateErrorKind.NETWORK, e.javaClass.simpleName, null)
            }
        }
    }

    /** The button: downloads [release] and installs it. */
    fun install(release: ReleaseInfo) {
        work?.cancel()
        _state.value = UpdateUi.Downloading(release, 0, release.apk.size.takeIf { it > 0 })
        work = viewModelScope.launch {
            try {
                val file = steps.download(release) { done, total ->
                    _state.value = UpdateUi.Downloading(release, done, total)
                }
                apk = file
                proceedToInstall(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                _state.value = failed(e.kind, e.detail, release)
            } catch (e: Exception) {
                _state.value = failed(UpdateErrorKind.DOWNLOAD_FAILED, e.javaClass.simpleName, release)
            }
        }
    }

    /** The viewer came back from the phone's "install unknown apps" page: go on if it was allowed. */
    fun onResumed() {
        val current = _state.value
        if (current is UpdateUi.NeedsPermission && steps.installAllowed()) {
            apk?.let { file -> work = viewModelScope.launch { proceedToInstall(current.release, file) } }
        }
    }

    /** The phone's page where installing from this app is allowed. */
    fun permissionSettings(): Intent = steps.permissionSettings()

    private suspend fun proceedToInstall(release: ReleaseInfo, file: File) {
        if (!steps.installAllowed()) {
            _state.value = UpdateUi.NeedsPermission(release)
            return
        }
        _state.value = UpdateUi.Installing(release)
        try {
            session = steps.install(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = failed(UpdateErrorKind.INSTALL_FAILED, e.message ?: e.javaClass.simpleName, release)
        }
    }

    private fun failed(kind: UpdateErrorKind, detail: String?, release: ReleaseInfo?) = UpdateUi.Failed(kind, shortDetail(detail), release)

    private fun currentRelease(): ReleaseInfo? = when (val s = _state.value) {
        is UpdateUi.Available -> s.release
        is UpdateUi.Downloading -> s.release
        is UpdateUi.NeedsPermission -> s.release
        is UpdateUi.Installing -> s.release
        is UpdateUi.Failed -> s.release
        else -> null
    }

    private fun backToAvailable() {
        session = null
        currentRelease()?.let { _state.value = UpdateUi.Available(steps.installed, it) }
    }

    override fun onCleared() {
        session?.let { steps.abandon(it) }
    }

    companion object {
        private const val MAX_DETAIL = 80

        /**
         * The short technical note under an update error, for someone who reports it. One line, cut at [MAX_DETAIL]
         * characters, and never an address or the name of where the updates come from: an error message from the network
         * layer can carry both, and the page does not tell the viewer where updates are kept.
         */
        internal fun shortDetail(detail: String?): String? {
            val line = detail?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
            if ("://" in line || line.contains("github", ignoreCase = true)) return null
            return if (line.length <= MAX_DETAIL) line else line.take(MAX_DETAIL - 1) + "…"
        }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { UpdateViewModel(container.updater) }
        }
    }
}

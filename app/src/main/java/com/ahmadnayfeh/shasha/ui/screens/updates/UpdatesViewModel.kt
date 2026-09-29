package com.ahmadnayfeh.shasha.ui.screens.updates

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.update.InstallEvent
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.update.UpdateException
import com.ahmadnayfeh.shasha.update.UpdateSteps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** What the updater screen shows (SPEC §6). */
sealed interface UpdateUiState {
    data object Checking : UpdateUiState

    /** This build has no read token: the private repository's releases cannot be read. */
    data object TokenMissing : UpdateUiState

    /** Nothing newer; [latest] is the newest published release (null: none to offer). */
    data class UpToDate(val latest: ReleaseInfo?) : UpdateUiState

    data class Available(val release: ReleaseInfo, val installAllowed: Boolean) : UpdateUiState

    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long?) : UpdateUiState

    /**
     * Handed to the system installer. [confirm]: its confirmation, to open now (the screen does, once);
     * [confirmationClosed]: it was shown and closed without an answer yet (cancelled, or still installing).
     */
    data class Installing(val release: ReleaseInfo, val confirm: Intent? = null, val confirmationClosed: Boolean = false) : UpdateUiState

    data class Installed(val release: ReleaseInfo) : UpdateUiState

    /** REQUEST_INSTALL_PACKAGES is not allowed: [command] allows it (adb). */
    data class InstallNotAllowed(val release: ReleaseInfo, val command: String) : UpdateUiState

    /** [release]: the one being downloaded or installed when it failed (null: the check itself failed). */
    data class Failed(val kind: UpdateErrorKind, val detail: String?, val release: ReleaseInfo?) : UpdateUiState
}

/**
 * «فحص التحديثات» (SPEC §6), adult profile only: checks at once, then «تنزيل وتثبيت» downloads, verifies
 * (same app, newer, same signing key) and hands the APK to the system installer, whose own confirmation the screen
 * opens. Leaving the screen cancels a download.
 */
class UpdatesViewModel(private val steps: UpdateSteps) : ViewModel() {
    private val _state = MutableStateFlow<UpdateUiState>(if (steps.hasToken) UpdateUiState.Checking else UpdateUiState.TokenMissing)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var work: Job? = null

    /** The installer session this screen waits for, and events that came before its id was known. */
    private var session: Int? = null
    private var sessionRelease: ReleaseInfo? = null
    private val early = LinkedHashMap<Int, InstallEvent>()

    init {
        viewModelScope.launch { steps.installEvents.collect(::onInstallEvent) }
        check()
    }

    /** Reads the latest release (again: «إعادة الفحص», «إعادة المحاولة» after a failed check). */
    fun check() {
        work?.cancel()
        if (!steps.hasToken) {
            _state.value = UpdateUiState.TokenMissing
            return
        }
        _state.value = UpdateUiState.Checking
        work = viewModelScope.launch {
            val next = try {
                val latest = steps.latest()
                if (latest != null && latest.isNewerThan(steps.installed.code)) {
                    UpdateUiState.Available(latest, steps.installAllowed())
                } else {
                    UpdateUiState.UpToDate(latest)
                }
            } catch (e: UpdateException) {
                ensureActive()
                UpdateUiState.Failed(e.kind, e.detail, release = null)
            }
            _state.value = next
        }
    }

    /**
     * «تنزيل وتثبيت», and «إعادة المحاولة» once a release is known (a file already downloaded and verified is
     * not downloaded again). A failed check is checked again instead.
     */
    fun install() {
        val release = when (val shown = _state.value) {
            is UpdateUiState.Available -> shown.release
            is UpdateUiState.InstallNotAllowed -> shown.release
            is UpdateUiState.Installing -> shown.release
            is UpdateUiState.Failed -> shown.release ?: return check()
            else -> null
        } ?: return
        work?.cancel()
        work = viewModelScope.launch {
            try {
                _state.value = UpdateUiState.Downloading(release, 0, release.apk.size.takeIf { it > 0 })
                val apk = steps.download(release) { done, total ->
                    if (isActive) _state.value = UpdateUiState.Downloading(release, done, total)
                }
                handOver(release, apk)
            } catch (e: UpdateException) {
                ensureActive()
                _state.value = UpdateUiState.Failed(e.kind, e.detail, release)
            }
        }
    }

    /** «إلغاء» while downloading: back to the offer. */
    fun cancelDownload() {
        val downloading = _state.value as? UpdateUiState.Downloading ?: return
        work?.cancel()
        _state.value = UpdateUiState.Available(downloading.release, steps.installAllowed())
    }

    /** The screen opened the system's confirmation: never open it twice. */
    fun onConfirmationShown() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        _state.value = installing.copy(confirm = null)
    }

    /** The system's confirmation closed; its answer (if any) comes as an [InstallEvent]. */
    fun onConfirmationClosed() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        _state.value = installing.copy(confirmationClosed = true)
    }

    /** This TV has no screen for the system's confirmation. */
    fun onConfirmationUnavailable() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        _state.value = UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, "ActivityNotFoundException", installing.release)
    }

    private suspend fun handOver(release: ReleaseInfo, apk: File) {
        if (!steps.installAllowed()) {
            _state.value = UpdateUiState.InstallNotAllowed(release, steps.installCommand)
            return
        }
        // A session the viewer never answered is dropped: this is a new try.
        session?.let(steps::abandon)
        session = null
        _state.value = UpdateUiState.Installing(release)
        val id = try {
            steps.install(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException(UpdateErrorKind.INSTALL_FAILED, e.javaClass.simpleName, e)
        }
        session = id
        sessionRelease = release
        early.remove(id)?.let(::apply)
    }

    private fun onInstallEvent(event: InstallEvent) {
        if (event.sessionId != session) {
            // Its session id is not known yet (the system can answer before install() returns).
            early[event.sessionId] = event
            while (early.size > MAX_EARLY_EVENTS) early.remove(early.keys.first())
            return
        }
        apply(event)
    }

    private fun apply(event: InstallEvent) {
        val release = sessionRelease ?: return
        when (event) {
            is InstallEvent.Confirm -> _state.value = UpdateUiState.Installing(release, confirm = event.intent)
            is InstallEvent.Installed -> _state.value = UpdateUiState.Installed(release)
            is InstallEvent.Cancelled -> {
                session = null
                _state.value = UpdateUiState.Available(release, steps.installAllowed())
            }
            is InstallEvent.Failed -> {
                session = null
                _state.value = UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, event.message ?: "status ${event.status}", release)
            }
        }
    }

    private companion object {
        const val MAX_EARLY_EVENTS = 8
    }
}

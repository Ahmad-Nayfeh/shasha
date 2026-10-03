package com.ahmadnayfeh.shasha.ui.screens.updates

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.update.InstallEvent
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.update.UpdateException
import com.ahmadnayfeh.shasha.update.UpdateSteps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the updater screen shows (SPEC §6). */
sealed interface UpdateUiState {
    /** Comparing the installed version with the latest release. */
    data object Checking : UpdateUiState

    /** This build has no read token (a local build): it cannot read releases, so it cannot update itself. */
    data object TokenMissing : UpdateUiState

    /** The installed version is the latest. */
    data object UpToDate : UpdateUiState

    /** A newer [release] is coming down. */
    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long?) : UpdateUiState

    /**
     * Handed to the system installer: the app is about to close for a moment and open again by itself. [confirm]: the
     * system's own confirmation (the fallback), to open now (the screen does, once); [asked]: it was asked for;
     * [confirmationClosed]: it was shown and closed without an answer (cancelled, or still installing).
     */
    data class Installing(
        val release: ReleaseInfo,
        val confirm: Intent? = null,
        val asked: Boolean = false,
        val confirmationClosed: Boolean = false,
    ) : UpdateUiState

    /** Installed, and this process still runs (rarely seen: the update replaces it). */
    data class Installed(val release: ReleaseInfo) : UpdateUiState

    /** The system does not allow this app to install yet: nothing was downloaded. */
    data class InstallNotAllowed(val release: ReleaseInfo) : UpdateUiState

    /** The viewer stopped the download, or said no in the system's confirmation: nothing changed. */
    data class Cancelled(val release: ReleaseInfo) : UpdateUiState

    /** [release]: the one being downloaded or installed when it failed (null: the check itself failed). */
    data class Failed(val kind: UpdateErrorKind, val release: ReleaseInfo?) : UpdateUiState
}

/**
 * «فحص التحديثات» (SPEC §6), adult profile only: one press. Opening it checks; when a newer version is out it is
 * downloaded, verified (same app, newer, same signing key) and handed to the system installer at once, with no second
 * button; the app then closes for a moment and opens again ([com.ahmadnayfeh.shasha.update.AppUpdatedReceiver]). Where
 * Android asks for its own confirmation, the screen opens it. Leaving the screen cancels a download.
 *
 * [handoverPauseMs]: how long «سيُغلق التطبيق لحظة ثم يُفتح من جديد» stays on screen before the APK is handed over.
 */
class UpdatesViewModel(
    private val steps: UpdateSteps,
    private val handoverPauseMs: Long = HANDOVER_PAUSE_MS,
) : ViewModel() {
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

    /** Compares the installed version with the latest release (again: «إعادة الفحص»); a newer one is installed at once. */
    fun check() {
        work?.cancel()
        if (!steps.hasToken) {
            _state.value = UpdateUiState.TokenMissing
            return
        }
        _state.value = UpdateUiState.Checking
        work = viewModelScope.launch {
            val latest = try {
                steps.latest()
            } catch (e: UpdateException) {
                ensureActive()
                fail(e, release = null)
                return@launch
            }
            if (latest != null && latest.isNewerThan(steps.installed.code)) {
                update(latest)
            } else {
                _state.value = UpdateUiState.UpToDate
            }
        }
    }

    /**
     * «إعادة المحاولة»: the update that stopped is tried again (a file already downloaded and verified is not downloaded
     * again); a failed check is checked again instead.
     */
    fun retry() {
        val release = when (val shown = _state.value) {
            is UpdateUiState.Failed -> shown.release
            is UpdateUiState.Cancelled -> shown.release
            is UpdateUiState.InstallNotAllowed -> shown.release
            is UpdateUiState.Installing -> shown.release
            else -> null
        } ?: return check()
        work?.cancel()
        work = viewModelScope.launch { update(release) }
    }

    /** «إلغاء» while downloading. */
    fun cancel() {
        val downloading = _state.value as? UpdateUiState.Downloading ?: return
        work?.cancel()
        _state.value = UpdateUiState.Cancelled(downloading.release)
    }

    /** The screen opened the system's confirmation: never open it twice. */
    fun onConfirmationShown() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        _state.value = installing.copy(confirm = null)
    }

    /** The system's confirmation closed; its answer (if any) comes as an [InstallEvent]. */
    fun onConfirmationClosed() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        _state.value = installing.copy(confirm = null, confirmationClosed = true)
    }

    /** This TV has no screen for the system's confirmation. */
    fun onConfirmationUnavailable() {
        val installing = _state.value as? UpdateUiState.Installing ?: return
        Log.w(TAG, "The system's install confirmation has no screen on this device")
        _state.value = UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, installing.release)
    }

    /** [release] to the end of what this screen does: checks that installing is allowed, downloads, verifies, hands over. */
    private suspend fun update(release: ReleaseInfo) {
        try {
            if (!steps.installAllowed()) {
                _state.value = UpdateUiState.InstallNotAllowed(release)
                return
            }
            _state.value = UpdateUiState.Downloading(release, 0, release.apk.size.takeIf { it > 0 })
            val apk = coroutineScope {
                steps.download(release) { done, total ->
                    if (isActive) _state.value = UpdateUiState.Downloading(release, done, total)
                }
            }
            // Say what is about to happen before the system takes over: the app closes for a moment, then opens again.
            _state.value = UpdateUiState.Installing(release)
            if (handoverPauseMs > 0) delay(handoverPauseMs)
            // A session the viewer never answered is dropped: this is a new try.
            session?.let(steps::abandon)
            session = null
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
        } catch (e: UpdateException) {
            currentCoroutineContext().ensureActive()
            fail(e, release)
        }
    }

    private fun fail(e: UpdateException, release: ReleaseInfo?) {
        Log.w(TAG, "The update stopped: ${e.message}", e.cause)
        _state.value = UpdateUiState.Failed(e.kind, release)
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
            is InstallEvent.Confirm -> _state.value = UpdateUiState.Installing(release, confirm = event.intent, asked = true)
            is InstallEvent.Installed -> _state.value = UpdateUiState.Installed(release)
            is InstallEvent.Cancelled -> {
                session = null
                _state.value = UpdateUiState.Cancelled(release)
            }
            is InstallEvent.Failed -> {
                session = null
                Log.w(TAG, "The system installer refused: status ${event.status}, ${event.message}")
                _state.value = UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, release)
            }
        }
    }

    companion object {
        private const val TAG = "Updates"
        private const val MAX_EARLY_EVENTS = 8

        /** Long enough to read the line that says the app closes and opens again, short enough not to feel stuck. */
        const val HANDOVER_PAUSE_MS = 2_500L
    }
}

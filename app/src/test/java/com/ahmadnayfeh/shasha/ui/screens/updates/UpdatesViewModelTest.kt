package com.ahmadnayfeh.shasha.ui.screens.updates

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.update.InstallEvent
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseApk
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.update.UpdateException
import com.ahmadnayfeh.shasha.update.UpdateSteps
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The updater as one press (SPEC §6): opening it checks; a newer release is downloaded, verified and handed to the
 * system at once, with no second button; the system's own confirmation is only the fallback; a stopped update is
 * tried again with «إعادة المحاولة».
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UpdatesViewModelTest {
    private val steps = FakeSteps()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handoverPauseMs: Long = 0) = UpdatesViewModel(steps, handoverPauseMs)

    @Test
    fun nothingNewer_isUpToDate_andNothingIsDownloaded() {
        steps.latest = { RELEASE.copy(versionCode = 1057, versionName = "1.1.1") }
        assertThat(viewModel().state.value).isEqualTo(UpdateUiState.UpToDate)
        // Never a downgrade.
        steps.latest = { RELEASE.copy(versionCode = 1000) }
        assertThat(viewModel().state.value).isEqualTo(UpdateUiState.UpToDate)
        assertThat(steps.progress).isEmpty()
        assertThat(steps.installs).isEmpty()
    }

    @Test
    fun checkingAgain_comparesAgain() {
        steps.latest = { RELEASE.copy(versionCode = 1057, versionName = "1.1.1") }
        val vm = viewModel()
        assertThat(steps.asked).isEqualTo(1)
        vm.check()
        assertThat(steps.asked).isEqualTo(2)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.UpToDate)
    }

    @Test
    fun aNewerRelease_isDownloadedAndInstalledAtOnce_withNoSecondPress() {
        val hold = CompletableDeferred<Unit>()
        steps.hold = hold
        lateinit var vm: UpdatesViewModel
        var whileDownloading: UpdateUiState? = null
        var whenHandedOver: UpdateUiState? = null
        steps.onDownload = { whileDownloading = vm.state.value }
        steps.onInstall = { whenHandedOver = vm.state.value }
        vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Checking)

        hold.complete(Unit) // the check's answer arrives: a newer release
        // The download started by itself (with its size known), and when the APK was handed over the screen was
        // already saying that the app closes for a moment and opens again.
        assertThat(whileDownloading).isEqualTo(UpdateUiState.Downloading(RELEASE, 0, APK_SIZE))
        assertThat(steps.progress).containsExactly(0L, APK_SIZE).inOrder()
        assertThat(whenHandedOver).isEqualTo(UpdateUiState.Installing(RELEASE))
        assertThat(steps.installs).containsExactly(steps.apk)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
    }

    @Test
    fun theMessage_staysUpBeforeTheHandOver() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val vm = viewModel(handoverPauseMs = 2_500)
        // «سيُغلق التطبيق لحظة ثم يُفتح من جديد» is on screen, and the APK is not handed over yet.
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
        assertThat(steps.installs).isEmpty()
        dispatcher.scheduler.advanceTimeBy(2_499)
        dispatcher.scheduler.runCurrent()
        assertThat(steps.installs).isEmpty()
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        assertThat(steps.installs).containsExactly(steps.apk)
    }

    @Test
    fun whereTheSystemStillAsks_itsWindowIsTheFallback_andTryingAgainOpensANewSession() {
        val vm = viewModel()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        steps.events.tryEmit(InstallEvent.Confirm(1, confirm))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE, confirm = confirm, asked = true))
        // The screen opens it once.
        vm.onConfirmationShown()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE, asked = true))
        // Closed without an answer: «إعادة المحاولة» is offered; it drops the unanswered session and installs again.
        vm.onConfirmationClosed()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE, asked = true, confirmationClosed = true))
        vm.retry()
        assertThat(steps.abandoned).containsExactly(1)
        assertThat(steps.installs).hasSize(2)
    }

    @Test
    fun theViewerSaysNo_inTheSystemsWindow_theUpdateIsCancelled_andNothingChanges() {
        val vm = viewModel()
        steps.events.tryEmit(InstallEvent.Cancelled(1))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Cancelled(RELEASE))
        vm.retry()
        assertThat(steps.installs).hasSize(2) // the file is kept: no second download is needed
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
    }

    @Test
    fun aRefusalByTheSystem_isAnError_withItsRetry() {
        val vm = viewModel()
        steps.events.tryEmit(InstallEvent.Failed(1, status = 4, message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, RELEASE))
        // Another session's answer changes nothing.
        steps.events.tryEmit(InstallEvent.Installed(99))
        assertThat(vm.state.value).isInstanceOf(UpdateUiState.Failed::class.java)
        vm.retry()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
    }

    @Test
    fun installedWhileThisProcessStillRuns_isSaid() {
        val vm = viewModel()
        steps.events.tryEmit(InstallEvent.Installed(1))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installed(RELEASE))
    }

    @Test
    fun theSystemsAnswer_beforeTheSessionIdIsKnown_isNotLost() {
        steps.answerAtOnce = { id -> InstallEvent.Confirm(id, Intent("confirm")) }
        val vm = viewModel()
        assertThat((vm.state.value as UpdateUiState.Installing).confirm?.action).isEqualTo("confirm")
    }

    @Test
    fun aFailedCheck_saysWhy_andRetryChecksAgain() {
        steps.latest = { throw UpdateException(UpdateErrorKind.NETWORK, "UnknownHostException") }
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.NETWORK, release = null))
        steps.latest = { RELEASE }
        vm.retry() // «إعادة المحاولة» of a failed check checks again, and the update goes on by itself
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
    }

    @Test
    fun aRefusedFile_isTheError_neverTheInstaller() {
        steps.download = { throw UpdateException(UpdateErrorKind.SIGNATURE_MISMATCH) }
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.SIGNATURE_MISMATCH, RELEASE))
        assertThat(steps.installs).isEmpty()
    }

    @Test
    fun aBrokenDownload_isTheError_andRetryDownloadsAgain() {
        var broken = true
        steps.download = { if (broken) throw UpdateException(UpdateErrorKind.DOWNLOAD_FAILED, "12 of 100 bytes") else steps.apk }
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.DOWNLOAD_FAILED, RELEASE))
        broken = false
        vm.retry()
        assertThat(steps.installs).containsExactly(steps.apk)
    }

    @Test
    fun installingNotAllowed_downloadsNothing_andRetryGoesOnOnceItIs() {
        steps.allowed = false
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.InstallNotAllowed(RELEASE))
        assertThat(steps.progress).isEmpty()
        assertThat(steps.installs).isEmpty()

        steps.allowed = true
        vm.retry()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
        assertThat(steps.installs).hasSize(1)
    }

    @Test
    fun cancellingTheDownload_stopsIt_andRetryStartsAgain() {
        val gate = CompletableDeferred<Unit>()
        steps.downloadGate = gate
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Downloading(RELEASE, 0, APK_SIZE))
        vm.cancel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Cancelled(RELEASE))
        assertThat(steps.downloadCancelled).isTrue()
        // Nothing else happens by itself afterwards.
        gate.complete(Unit)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Cancelled(RELEASE))
        assertThat(steps.installs).isEmpty()

        steps.downloadGate = null
        vm.retry()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
    }

    @Test
    fun theInstallerRefusingToStart_isAnInstallError() {
        steps.installError = IllegalStateException("no session")
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, RELEASE))
    }

    @Test
    fun aTvWithNoScreenForTheSystemsWindow_isAnInstallError() {
        val vm = viewModel()
        steps.events.tryEmit(InstallEvent.Confirm(1, Intent("confirm")))
        vm.onConfirmationUnavailable()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, RELEASE))
    }

    private class FakeSteps : UpdateSteps {
        var asked = 0
        var allowed = true
        var hold: CompletableDeferred<Unit>? = null
        var latest: () -> ReleaseInfo = { RELEASE }
        val apk = File("shasha-1.2.0-1070.apk")
        var download: () -> File = { apk }
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadCancelled = false
        var onDownload: () -> Unit = {}
        var onInstall: () -> Unit = {}
        var installError: Exception? = null
        val progress = mutableListOf<Long>()
        val installs = mutableListOf<File>()
        val abandoned = mutableListOf<Int>()
        var answerAtOnce: ((Int) -> InstallEvent)? = null
        val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)

        override val installed = InstalledVersion("1.1.1", 1057)
        override val installEvents = events

        override suspend fun latest(): ReleaseInfo {
            asked++
            hold?.await()
            return latest.invoke()
        }

        override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File {
            onDownload()
            onProgress(0, APK_SIZE)
            try {
                downloadGate?.await()
            } catch (e: CancellationException) {
                downloadCancelled = true
                throw e
            }
            onProgress(APK_SIZE, APK_SIZE)
            progress += listOf(0L, APK_SIZE)
            return download.invoke()
        }

        override fun installAllowed() = allowed

        override suspend fun install(apk: File): Int {
            onInstall()
            installError?.let { throw it }
            installs += apk
            val id = installs.size
            // The system can answer before this returns.
            answerAtOnce?.let { events.tryEmit(it(id)) }
            return id
        }

        override fun abandon(sessionId: Int) {
            abandoned += sessionId
        }
    }

    private companion object {
        const val APK_SIZE = 18_874_368L
        val RELEASE = ReleaseInfo(
            versionName = "1.2.0",
            versionCode = 1070,
            apk = ReleaseApk("https://downloads.example.test/shasha-tv.apk?v=1070", APK_SIZE, "abababababababababababababababababababababababababababababababab"),
        )
    }
}

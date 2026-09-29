package com.ahmadnayfeh.shasha.ui.screens.updates

import android.app.Application
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.update.InstallEvent
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseAsset
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import com.ahmadnayfeh.shasha.update.UpdateException
import com.ahmadnayfeh.shasha.update.UpdateSteps
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The updater's steps as the screen goes through them (SPEC §6): check, offer, download, the system's own confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UpdatesViewModelTest {
    private val steps = FakeSteps()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun withoutAToken_nothingIsAsked() {
        steps.token = false
        val vm = UpdatesViewModel(steps)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.TokenMissing)
        vm.check()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.TokenMissing)
        assertThat(steps.asked).isEqualTo(0)
    }

    @Test
    fun nothingNewer_isUpToDate() {
        steps.latest = { RELEASE.copy(versionCode = 1041) }
        assertThat(UpdatesViewModel(steps).state.value).isEqualTo(UpdateUiState.UpToDate(RELEASE.copy(versionCode = 1041)))
        steps.latest = { null }
        assertThat(UpdatesViewModel(steps).state.value).isEqualTo(UpdateUiState.UpToDate(null))
    }

    @Test
    fun aFailedCheck_saysWhy_andRetriesTheCheck() {
        steps.latest = { throw UpdateException(UpdateErrorKind.TOKEN_REJECTED, "HTTP 401") }
        val vm = UpdatesViewModel(steps)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.TOKEN_REJECTED, "HTTP 401", release = null))
        steps.latest = { RELEASE }
        vm.install() // «إعادة المحاولة» of a failed check checks again
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Available(RELEASE, installAllowed = true))
    }

    @Test
    fun aNewerRelease_isDownloaded_andHandedToTheSystem_whichAsksTheViewer() {
        val vm = UpdatesViewModel(steps)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Available(RELEASE, installAllowed = true))

        vm.install()
        assertThat(steps.progress).containsExactly(0L, APK_SIZE).inOrder()
        assertThat(steps.installs).containsExactly(steps.apk)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))

        // The system's confirmation: opened once by the screen.
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        steps.events.tryEmit(InstallEvent.Confirm(1, confirm))
        assertThat((vm.state.value as UpdateUiState.Installing).confirm).isSameInstanceAs(confirm)
        vm.onConfirmationShown()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))

        // The viewer said no: offered again (the file is kept).
        steps.events.tryEmit(InstallEvent.Cancelled(1))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Available(RELEASE, installAllowed = true))

        // Another try, refused by the system: its own message is shown.
        vm.install()
        steps.events.tryEmit(InstallEvent.Failed(2, status = 4, message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.INSTALL_FAILED, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", RELEASE))
        // Another session's answer changes nothing.
        steps.events.tryEmit(InstallEvent.Installed(99))
        assertThat(vm.state.value).isInstanceOf(UpdateUiState.Failed::class.java)
    }

    @Test
    fun theSystemsAnswer_beforeTheSessionIdIsKnown_isNotLost() {
        steps.answerAtOnce = { id -> InstallEvent.Confirm(id, Intent("confirm")) }
        val vm = UpdatesViewModel(steps)
        vm.install()
        assertThat((vm.state.value as UpdateUiState.Installing).confirm?.action).isEqualTo("confirm")
    }

    @Test
    fun aConfirmationThatClosedWithoutAnswer_offersToTryAgain_inANewSession() {
        val vm = UpdatesViewModel(steps)
        vm.install()
        vm.onConfirmationClosed()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE, confirmationClosed = true))
        vm.install()
        assertThat(steps.abandoned).containsExactly(1)
        assertThat(steps.installs).hasSize(2)
    }

    @Test
    fun aRefusedFile_isTheError_neverTheInstaller() {
        steps.download = { throw UpdateException(UpdateErrorKind.SIGNATURE_MISMATCH) }
        val vm = UpdatesViewModel(steps)
        vm.install()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Failed(UpdateErrorKind.SIGNATURE_MISMATCH, null, RELEASE))
        assertThat(steps.installs).isEmpty()
    }

    @Test
    fun installingNotAllowed_showsTheAdbCommand_thenTriesAgain() {
        steps.allowed = false
        val vm = UpdatesViewModel(steps)
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Available(RELEASE, installAllowed = false))
        vm.install()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.InstallNotAllowed(RELEASE, COMMAND))
        assertThat(steps.installs).isEmpty()

        steps.allowed = true
        vm.install()
        assertThat(vm.state.value).isEqualTo(UpdateUiState.Installing(RELEASE))
        assertThat(steps.installs).hasSize(1)
    }

    private class FakeSteps : UpdateSteps {
        var token = true
        var asked = 0
        var allowed = true
        var latest: () -> ReleaseInfo? = { RELEASE }
        val apk = File("shasha-1.0.0-1042.apk")
        var download: () -> File = { apk }
        val progress = mutableListOf<Long>()
        val installs = mutableListOf<File>()
        val abandoned = mutableListOf<Int>()
        var answerAtOnce: ((Int) -> InstallEvent)? = null
        val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)

        override val installed = InstalledVersion("1.0.0", 1041)
        override val hasToken: Boolean get() = token
        override val installCommand = COMMAND
        override val installEvents = events

        override suspend fun latest(): ReleaseInfo? {
            asked++
            return latest.invoke()
        }

        override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File {
            onProgress(0, APK_SIZE)
            onProgress(APK_SIZE, APK_SIZE)
            progress += listOf(0L, APK_SIZE)
            return download.invoke()
        }

        override fun installAllowed() = allowed

        override suspend fun install(apk: File): Int {
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
        const val COMMAND = "adb shell appops set com.ahmadnayfeh.shasha REQUEST_INSTALL_PACKAGES allow"
        val RELEASE = ReleaseInfo(
            tag = "v1.0.0-1042",
            versionName = "1.0.0",
            versionCode = 1042,
            notes = "شاشة 1.0.0 (1042)",
            apk = ReleaseAsset(7, "shasha-1.0.0-1042.apk", APK_SIZE, "https://api.github.com/repos/Ahmad-Nayfeh/shasha/releases/assets/7"),
        )
    }
}

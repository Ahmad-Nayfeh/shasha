package com.ahmadnayfeh.shasha.mobile.update

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.ui.update.UpdateUi
import com.ahmadnayfeh.shasha.mobile.ui.update.UpdateViewModel
import com.ahmadnayfeh.shasha.mobile.ui.update.updateErrorText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UpdateViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val release = RELEASE

    private class FakeSteps(override val installed: InstalledVersion) : UpdateSteps {
        val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
        override val installEvents: Flow<InstallEvent> = events
        var latest: suspend () -> ReleaseInfo = { RELEASE.copy(versionCode = installed.code) }
        var download: suspend (ReleaseInfo, (Long, Long?) -> Unit) -> File = { _, _ -> File("update.apk") }
        var allowed = true
        var installSession = 11
        var installError: Exception? = null
        val installed_ = ArrayList<File>()
        val abandoned = ArrayList<Int>()
        val permissionIntent = Intent("settings")

        override suspend fun latest(): ReleaseInfo = latest.invoke()
        override suspend fun download(release: ReleaseInfo, onProgress: (Long, Long?) -> Unit): File = download.invoke(release, onProgress)
        override fun installAllowed(): Boolean = allowed
        override fun permissionSettings(): Intent = permissionIntent
        override suspend fun install(apk: File): Int {
            installError?.let { throw it }
            installed_ += apk
            return installSession
        }

        override fun abandon(sessionId: Int) {
            abandoned += sessionId
        }
    }

    private val steps = FakeSteps(InstalledVersion("1.2.0", 1000))

    @Test
    fun theSameOrAnOlderReleaseMeansUpToDate() {
        steps.latest = { release.copy(versionCode = 1000) }
        assertThat(UpdateViewModel(steps).state.value).isEqualTo(UpdateUi.UpToDate(steps.installed))

        steps.latest = { release.copy(versionCode = 999) }
        assertThat(UpdateViewModel(steps).state.value).isEqualTo(UpdateUi.UpToDate(steps.installed))
    }

    @Test
    fun aNewerReleaseIsOfferedWithOneButton() {
        steps.latest = { release }

        val vm = UpdateViewModel(steps)

        assertThat(vm.state.value).isEqualTo(UpdateUi.Available(steps.installed, release))
    }

    @Test
    fun checkingShowsItselfWhileTheServerAnswers() {
        val answer = CompletableDeferred<ReleaseInfo>()
        steps.latest = { answer.await() }

        val vm = UpdateViewModel(steps)

        assertThat(vm.state.value).isEqualTo(UpdateUi.Checking)
        answer.complete(release)
        assertThat(vm.state.value).isEqualTo(UpdateUi.Available(steps.installed, release))
    }

    @Test
    fun aFailedCheckSaysWhyAndCheckAgainWorks() {
        steps.latest = { throw UpdateException(UpdateErrorKind.NETWORK, "UnknownHostException") }
        val vm = UpdateViewModel(steps)
        assertThat(vm.state.value).isEqualTo(UpdateUi.Failed(UpdateErrorKind.NETWORK, "UnknownHostException", null))

        steps.latest = { release }
        vm.check()

        assertThat(vm.state.value).isEqualTo(UpdateUi.Available(steps.installed, release))
    }

    @Test
    fun theDetailUnderAnErrorNeverCarriesAnAddressOrWhereUpdatesAreKept() {
        for (detail in listOf(
            "url \"https://example.org/shasha-mobile.apk\"",
            "Unexpected JSON token: <html>GitHub is down</html>",
            "see https://example.org/x",
        )) {
            steps.latest = { throw UpdateException(UpdateErrorKind.BAD_ANSWER, detail) }

            val state = UpdateViewModel(steps).state.value as UpdateUi.Failed

            assertThat(state.kind).isEqualTo(UpdateErrorKind.BAD_ANSWER)
            assertThat(state.detail).isNull()
        }
    }

    @Test
    @Config(qualifiers = "ar")
    fun theSitesAnswers_areSaidInArabic() {
        // UpdateFeed: 403/429 and 404 → FORBIDDEN / NOT_FOUND, 5xx or a broken manifest or no "mobile" entry → BAD_ANSWER /
        // NO_APK, a short download or another file's SHA-256 → DOWNLOAD_FAILED.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val expected = mapOf(
            UpdateErrorKind.NETWORK to "تعذّر الوصول إلى خادم التحديثات. تحقق من الإنترنت.",
            UpdateErrorKind.FORBIDDEN to "تعذّرت قراءة التحديثات الآن.",
            UpdateErrorKind.NOT_FOUND to "تعذّرت قراءة التحديثات الآن.",
            UpdateErrorKind.BAD_ANSWER to "لم يُفهم ردّ خادم التحديثات.",
            UpdateErrorKind.NO_APK to "لم يُفهم ردّ خادم التحديثات.",
            UpdateErrorKind.DOWNLOAD_FAILED to "انقطع التنزيل. حاول مرة أخرى.",
        )
        for ((kind, text) in expected) {
            assertThat(context.getString(updateErrorText(kind))).isEqualTo(text)
        }
        for (kind in UpdateErrorKind.entries) {
            assertThat(context.getString(updateErrorText(kind))).containsMatch("\\p{InArabic}")
        }
    }

    @Test
    fun theDetailIsOneShortLine() {
        assertThat(UpdateViewModel.shortDetail("HTTP 404")).isEqualTo("HTTP 404")
        assertThat(UpdateViewModel.shortDetail(null)).isNull()
        assertThat(UpdateViewModel.shortDetail("  \n  ")).isNull()
        assertThat(UpdateViewModel.shortDetail("first line\nsecond line")).isEqualTo("first line")
        val long = UpdateViewModel.shortDetail("x".repeat(200))!!
        assertThat(long).hasLength(80)
        assertThat(long).endsWith("…")
    }

    @Test
    fun theButtonDownloadsThenHandsTheFileToTheInstaller() {
        steps.latest = { release }
        steps.download = { _, onProgress ->
            onProgress(1_000, 5_000)
            File("shasha-mobile.apk")
        }
        val vm = UpdateViewModel(steps)

        vm.install(release)

        assertThat(steps.installed_).containsExactly(File("shasha-mobile.apk"))
        assertThat(vm.state.value).isEqualTo(UpdateUi.Installing(release))
    }

    @Test
    fun theDownloadReportsItsProgress() {
        steps.latest = { release }
        val gate = CompletableDeferred<Unit>()
        steps.download = { _, onProgress ->
            onProgress(2_500, 5_000)
            gate.await()
            File("a.apk")
        }
        val vm = UpdateViewModel(steps)

        vm.install(release)

        assertThat(vm.state.value).isEqualTo(UpdateUi.Downloading(release, 2_500, 5_000))
        gate.complete(Unit)
        assertThat(vm.state.value).isEqualTo(UpdateUi.Installing(release))
    }

    @Test
    fun withoutPermissionToInstallThePageAsksAndGoesOnWhenTheViewerComesBack() {
        steps.latest = { release }
        steps.allowed = false
        val vm = UpdateViewModel(steps)

        vm.install(release)

        assertThat(vm.state.value).isEqualTo(UpdateUi.NeedsPermission(release))
        assertThat(steps.installed_).isEmpty()
        assertThat(vm.permissionSettings()).isSameInstanceAs(steps.permissionIntent)

        vm.onResumed() // back from the phone's page, still not allowed
        assertThat(vm.state.value).isEqualTo(UpdateUi.NeedsPermission(release))

        steps.allowed = true
        vm.onResumed()
        assertThat(vm.state.value).isEqualTo(UpdateUi.Installing(release))
        assertThat(steps.installed_).hasSize(1)
    }

    @Test
    fun aDownloadThatFailsOrIsRefusedCanBeTriedAgain() {
        steps.latest = { release }
        steps.download = { _, _ -> throw UpdateException(UpdateErrorKind.SIGNATURE_MISMATCH) }
        val vm = UpdateViewModel(steps)

        vm.install(release)

        assertThat(vm.state.value).isEqualTo(UpdateUi.Failed(UpdateErrorKind.SIGNATURE_MISMATCH, null, release))
    }

    @Test
    fun theSystemsConfirmationDialogIsPassedToTheScreen() {
        steps.latest = { release }
        val vm = UpdateViewModel(steps)
        val seen = ArrayList<Intent>()
        val job = CoroutineScope(main.dispatcher).launch { vm.confirmations.collect { seen += it } }
        vm.install(release)
        val confirm = Intent("confirm")

        steps.events.tryEmit(InstallEvent.Confirm(sessionId = 11, intent = confirm))

        assertThat(seen).containsExactly(confirm)
        job.cancel()
    }

    @Test
    fun sayingNoInTheSystemsDialogGoesBackToTheOffer() {
        steps.latest = { release }
        val vm = UpdateViewModel(steps)
        vm.install(release)

        steps.events.tryEmit(InstallEvent.Cancelled(11))

        assertThat(vm.state.value).isEqualTo(UpdateUi.Available(steps.installed, release))
    }

    @Test
    fun aFailureOfTheInstallerIsReportedWithItsMessage() {
        steps.latest = { release }
        val vm = UpdateViewModel(steps)
        vm.install(release)

        steps.events.tryEmit(InstallEvent.Failed(11, status = 6, message = "INSTALL_FAILED_VERSION_DOWNGRADE"))

        assertThat(vm.state.value).isEqualTo(UpdateUi.Failed(UpdateErrorKind.INSTALL_FAILED, "INSTALL_FAILED_VERSION_DOWNGRADE", release))
    }

    @Test
    fun eventsOfAnotherSessionAreIgnored() {
        steps.latest = { release }
        val vm = UpdateViewModel(steps)
        vm.install(release)

        steps.events.tryEmit(InstallEvent.Cancelled(99))

        assertThat(vm.state.value).isEqualTo(UpdateUi.Installing(release))
    }

    @Test
    fun anInstallerThatCannotStartIsAFailure() {
        steps.latest = { release }
        steps.installError = IllegalStateException("no session")
        val vm = UpdateViewModel(steps)

        vm.install(release)

        assertThat(vm.state.value).isEqualTo(UpdateUi.Failed(UpdateErrorKind.INSTALL_FAILED, "no session", release))
    }
}

private val RELEASE = ReleaseInfo(
    versionName = "1.2.0", versionCode = 1042, notes = "",
    apk = ReleaseApk("https://downloads.example/shasha-mobile.apk?v=1042", 5_000, "ab".repeat(32)),
)

package com.ahmadnayfeh.shasha.update

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.FloorAwareRobolectricRunner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * What the installer calls, on Fire OS 6 / Android 7.1 (API 25) as well as on the newest Android: nothing newer than the
 * device may be called (a NoSuchMethodError there would stop the update before it began), the update is committed, and the
 * app knows it should open itself again afterwards. On API 25 Robolectric loads that version's real framework classes, so
 * an unguarded newer call fails here. API 25 runs as soon as the app's minSdk is 25 ([FloorAwareRobolectricRunner]); API 35
 * always.
 */
@RunWith(FloorAwareRobolectricRunner::class)
@Config(sdk = [25, 35], application = Application::class)
class UpdateInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installer = UpdateInstaller(context)

    private fun apk(): File = tmp.newFile("shasha-1.2.0-1070.apk").apply { writeBytes(ByteArray(2_048) { it.toByte() }) }

    @Test
    fun installingIsAllowed_beforeAndroid8_whereThereIsNoSwitch_andFollowsTheSwitchAfter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
            assertThat(installer.installAllowed()).isFalse()
            shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
            assertThat(installer.installAllowed()).isTrue()
        } else {
            // canRequestPackageInstalls() does not exist before API 26: asking it would crash.
            assertThat(installer.installAllowed()).isTrue()
        }
    }

    @Test
    fun theApkIsCommittedInASession_onEveryApi_andTheAppIsToldToOpenAgain() {
        val id = runBlocking { installer.install(apk()) }
        val session = context.packageManager.packageInstaller.getSessionInfo(id)
        assertThat(session).isNotNull()
        assertThat(session!!.appPackageName).isEqualTo(context.packageName)
        assertThat(RelaunchAfterUpdate(context).take()).isTrue()
    }

    @Test
    fun anApkThatCannotBeRead_isAnError_andNothingIsLeftArmed() {
        assertThrows(FileNotFoundException::class.java) { runBlocking { installer.install(File(tmp.root, "missing.apk")) } }
        assertThat(RelaunchAfterUpdate(context).take()).isFalse()
    }
}

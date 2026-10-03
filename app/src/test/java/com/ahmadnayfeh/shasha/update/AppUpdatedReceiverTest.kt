package com.ahmadnayfeh.shasha.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.MainActivity
import com.ahmadnayfeh.shasha.FloorAwareRobolectricRunner
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * After an update the app started itself, the new version opens «شاشة» again: only that update, only once, and only
 * soon after (an update asked for in the app is the one that reopens it; the installer kit's reinstall starts the app on
 * its own). The system's broadcast reaches the new version; here it is delivered by hand, on API 25 (once the app's minSdk
 * is 25: [FloorAwareRobolectricRunner]) and on the newest.
 */
@RunWith(FloorAwareRobolectricRunner::class)
@Config(sdk = [25, 35], application = Application::class)
class AppUpdatedReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = shadowOf(context as Application)

    private fun replaced() = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)

    @Test
    fun anUpdateTheAppStarted_opensTheAppAgain_once() {
        RelaunchAfterUpdate(context).arm()
        AppUpdatedReceiver().onReceive(context, replaced())
        val started = application.nextStartedActivity
        assertThat(started.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        // The note was used up: the next replacement (the kit's reinstall, say) opens nothing.
        AppUpdatedReceiver().onReceive(context, replaced())
        assertThat(application.nextStartedActivity).isNull()
    }

    @Test
    fun aReplacementTheAppDidNotStart_opensNothing() {
        AppUpdatedReceiver().onReceive(context, replaced())
        assertThat(application.nextStartedActivity).isNull()
    }

    @Test
    fun otherBroadcasts_openNothing_andKeepTheNote() {
        RelaunchAfterUpdate(context).arm()
        AppUpdatedReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertThat(application.nextStartedActivity).isNull()
        assertThat(RelaunchAfterUpdate(context).take()).isTrue()
    }

    @Test
    fun theNote_expires_soSomeLaterReplacementNeverOpensTheApp() {
        var now = 1_000_000L
        val note = RelaunchAfterUpdate(context) { now }
        note.arm()
        now += RelaunchAfterUpdate.VALID_MS
        assertThat(note.take()).isTrue() // right at the limit it still counts
        note.arm()
        now += RelaunchAfterUpdate.VALID_MS + 1
        assertThat(note.take()).isFalse()
        assertThat(note.take()).isFalse() // and it is gone
    }

    @Test
    fun aNoteCanBeWithdrawn_whenTheInstallNeverStarted() {
        val note = RelaunchAfterUpdate(context)
        note.arm()
        note.disarm()
        assertThat(note.take()).isFalse()
    }

    @Test
    fun theManifest_declaresTheReceiverForTheNewVersion_andThePermissionForSilentUpdates() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION")
        assertThat(manifest).contains("android.permission.REQUEST_INSTALL_PACKAGES")
        val receiver = Regex("""<receiver\s+android:name="\.update\.AppUpdatedReceiver"\s+android:exported="false">\s*<intent-filter>\s*<action android:name="android\.intent\.action\.MY_PACKAGE_REPLACED" />\s*</intent-filter>\s*</receiver>""")
        assertThat(receiver.containsMatchIn(manifest)).isTrue()
    }
}

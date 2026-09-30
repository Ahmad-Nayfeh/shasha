package com.ahmadnayfeh.shasha.lock

import android.app.AppOpsManager
import android.app.Service
import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.ShashaApp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * The guard never runs without the two permissions the owner granted it (SPEC §4): restarted by the system
 * after they were taken back, it stops at once and asks not to be restarted again; running, it stops as soon
 * as one of them is taken back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35]) // The manifest's ShashaApp: in tests its onCreate starts nothing.
class GuardServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var containerUsed = false

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
        if (containerUsed) {
            val container = (app as ShashaApp).container
            container.appScope.cancel()
            container.database.close()
        }
    }

    @Test
    fun restartedWithoutItsPermissions_itStopsAtOnce_andIsNotRestartedAgain() {
        ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildService(GuardService::class.java).create()
        val service = controller.get()
        // Its notification was shown first (a foreground service must), then it stopped.
        assertThat(shadowOf(service).lastForegroundNotification).isNotNull()
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        assertThat(service.onStartCommand(null, 0, 1)).isEqualTo(Service.START_NOT_STICKY)
        controller.destroy()
        assertThat(GuardService.running).isFalse()
    }

    @Test
    fun usageAccessTakenBack_theRunningGuardStopsItself() {
        ShadowSettings.setCanDrawOverlays(true)
        containerUsed = true
        // Read here once, so the guard's own first read (off the main thread) has nothing left to do.
        (app as ShashaApp).container.currentProfile
        val controller = Robolectric.buildService(GuardService::class.java).create()
        val service = controller.get()
        assertThat(service.onStartCommand(null, 0, 1)).isEqualTo(Service.START_STICKY)
        assertThat(shadowOf(service).isStoppedBySelf).isFalse()
        assertThat(GuardService.running).isTrue()

        // The TV's settings: Special app access > Usage access > off.
        val ops = app.getSystemService(AppOpsManager::class.java)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        controller.destroy()
        assertThat(GuardService.running).isFalse()
    }
}

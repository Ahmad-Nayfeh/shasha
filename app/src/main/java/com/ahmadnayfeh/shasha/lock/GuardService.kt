package com.ahmadnayfeh.shasha.lock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ahmadnayfeh.shasha.MainActivity
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.policy.LockState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPEC §4's guard outside the app (best effort, ADB permissions): while something is enforced
 * ([com.ahmadnayfeh.shasha.policy.PolicyEngine.guardShouldEnforce]: a locked video, "time up", outside the
 * schedule) and another app takes the front, it covers that app at once ([GuardOverlay]) and brings Shasha
 * back ([MainActivity]); every return is measured ([GuardReturnTimes], «حالة الحماية»). The decisions are
 * [GuardDecider]'s; the app in front comes from UsageStats ([UsageStatsProbe]).
 *
 * It runs whenever it can work (usage access and "display over other apps", [ProtectionChecks.guardCanRun]):
 * started with the app, after a reboot and after an update ([BootReceiver]), and restarted by the system if
 * it is killed (START_STICKY). Idle while nothing is enforced or Shasha is in front: it looks at nothing
 * then, it only follows the app's state. A foreground service ("specialUse"): its notification is quiet.
 */
class GuardService : LifecycleService() {
    private val handler = Handler(Looper.getMainLooper())
    private val clock = GuardClock.System
    private lateinit var decider: GuardDecider
    private lateinit var probe: ForegroundProbe
    private lateinit var overlay: GuardOverlay
    private lateinit var power: PowerManager
    private val timing = GuardTiming()

    private var enforced = false
    private var enforcedSinceMs = 0L
    private val nextStep = Runnable { step() }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = step()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()
        power = getSystemService(PowerManager::class.java)
        decider = GuardDecider(packageName, timing)
        probe = UsageStatsProbe(getSystemService(UsageStatsManager::class.java), clock)
        overlay = GuardOverlay(this, handler) { decider.onCovered(clock.elapsedMs()) }
        ContextCompat.registerReceiver(
            this,
            screenChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val container = appContainer
        lifecycleScope.launch {
            // The current profile is read from disk the first time: never on the main thread.
            withContext(Dispatchers.IO) { container.currentProfile }
            launch { container.appVisibility.state.collect { step() } }
            combine(container.lock.lock, container.session.state) { lock, state -> container.policyEngine.guardShouldEnforce(LockState.of(lock, state)) }
                .distinctUntilChanged()
                .collect { now ->
                    if (now && !enforced) enforcedSinceMs = clock.elapsedMs()
                    enforced = now
                    step()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(nextStep)
        overlay.hide()
        runCatching { unregisterReceiver(screenChanges) }
        super.onDestroy()
    }

    /** One look: what the decider says now, carried out; the next look scheduled when it asks for one. */
    private fun step() {
        if (!::decider.isInitialized) return
        handler.removeCallbacks(nextStep)
        val visible = appContainer.appVisibility.state.value
        val screenOn = power.isInteractive
        val needsFront = enforced && screenOn && !visible.resumed
        val input = GuardInput(
            nowMs = clock.elapsedMs(),
            enforced = enforced,
            enforcedSinceMs = enforcedSinceMs,
            appResumed = visible.resumed,
            appLeftAtMs = visible.leftAtMs,
            screenOn = screenOn,
            front = if (needsFront) probe.front() else null,
        )
        val out = decider.step(input)
        if (out.hideOverlay) overlay.hide()
        if (out.showOverlay) overlay.show(timing.overlayTimeoutMs)
        if (out.launchApp) bringBack()
        out.returned?.let(::record)
        out.nextCheckMs?.let { handler.postDelayed(nextStep, it) }
    }

    /** Shasha's activity to the front (allowed from the background: SYSTEM_ALERT_WINDOW is granted). */
    private fun bringBack() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not bring Shasha back", e)
        }
    }

    private fun record(back: GuardReturn) {
        Log.i(TAG, "Back from ${back.fromPackage.ifEmpty { "?" }} in ${back.backMs} ms (seen after ${back.detectMs} ms, covered after ${back.coverMs ?: "-"} ms)")
        val container = appContainer
        val time = ReturnTime(clock.wallMs(), back.fromPackage, back.detectMs, back.coverMs, back.backMs)
        container.appScope.launch {
            runCatching { container.guardReturnTimes.add(time) }.onFailure { Log.w(TAG, "Could not store a return time", it) }
        }
    }

    private fun startInForeground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.guard_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // The type is required from Android 14; below it the manifest's type is only informative.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 4

        /** The guard runs in this process now («حالة الحماية»). */
        @Volatile
        var running = false
            private set

        /**
         * Starts the guard when it can work (both ADB permissions). Safe to call often: from the app's start,
         * its activity, after a reboot. Refused starts (a system that forbids it from the background) are
         * logged; the next call tries again.
         */
        fun start(context: Context) {
            if (!ProtectionChecks.guardCanRun(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (API 31+) or a SecurityException.
                Log.w(TAG, "Could not start the guard", e)
            }
        }
    }
}

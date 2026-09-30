package com.ahmadnayfeh.shasha.lock

import android.app.AppOpsManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPEC §4's guard outside the app (best effort, ADB permissions): while something is enforced ([guardDemand]:
 * a locked video, "time up", outside the schedule, or, owner decision, a child profile being current at all until
 * the parent PIN lets it out) and another app takes the front, it covers that app at once ([GuardOverlay]) and
 * brings Shasha back ([MainActivity]); every return is measured ([GuardReturnTimes], «حالة الحماية»). The decisions
 * are [GuardDecider]'s; the app in front comes from UsageStats ([UsageStatsProbe]).
 *
 * It runs only while it can work (usage access and "display over other apps", [ProtectionChecks.guardCanRun]):
 * started with the app, after a reboot and after an update ([BootReceiver]), and restarted by the system if
 * it is killed (START_STICKY). When either permission is taken back (adb-setup --undo, the TV's settings) it
 * stops itself instead of acting without it. Idle while nothing is enforced, Shasha is in front, the screen
 * is off or the screensaver shows: it looks at nothing then, it only follows the app's state. A foreground
 * service ("specialUse"): its notification is quiet.
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

    /** The cover's second line: how a child gets out, while a child profile is what is enforced. */
    private var coverDetail: String? = null

    /** The screensaver is showing (between its two broadcasts; PowerManager counts it as interactive). */
    private var dreaming = false

    /** The screen was on at the last look. */
    private var interactive = false

    /** When the screen last became usable: on, the screensaver ended, or the guard started with it on. */
    private var activeSinceMs: Long? = null

    /** Stopping itself (its permissions are gone): no more looks. */
    private var stopping = false

    private val nextStep = Runnable { step() }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> becameActive()
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> becameActive()
            }
            step()
        }
    }
    private val checkPermissions = Runnable { if (!ProtectionChecks.guardCanRun(this)) stopGuard() }

    // Called on a binder thread.
    private val opsChanged = AppOpsManager.OnOpChangedListener { _, _ -> handler.post(checkPermissions) }

    override fun onCreate() {
        super.onCreate()
        running = true
        // First, even to stop at once: a service started with startForegroundService must call startForeground.
        startInForeground()
        power = getSystemService(PowerManager::class.java)
        decider = GuardDecider(packageName, timing)
        probe = UsageStatsProbe(getSystemService(UsageStatsManager::class.java), clock)
        overlay = GuardOverlay(this, handler) { decider.onCovered(clock.elapsedMs()) }
        if (!ProtectionChecks.guardCanRun(this)) {
            // Restarted by the system (START_STICKY) after its permissions were taken back.
            stopGuard()
            return
        }
        interactive = power.isInteractive
        if (interactive) activeSinceMs = clock.elapsedMs()
        ContextCompat.registerReceiver(
            this,
            screenChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchPermissions()
        val container = appContainer
        lifecycleScope.launch {
            // The current profile is read from disk the first time: never on the main thread.
            withContext(Dispatchers.IO) { container.currentProfile }
            // MainActivity reports its pause from onPause, and the next app resumes only once that returns: the
            // look (a UsageStats query) comes right after, never inside it.
            launch { container.appVisibility.state.collect { stepSoon() } }
            guardDemand(
                container.policyEngine,
                container.lock.lock,
                container.session.state,
                container.currentProfile.profile,
                container.childLetOut,
            ).collect { demand ->
                val now = demand.enforce
                if (now && !enforced) {
                    enforcedSinceMs = clock.elapsedMs()
                    if (container.appVisibility.state.value.resumed) seedProbe()
                }
                enforced = now
                coverDetail = if (demand.child) getString(R.string.guard_cover_child) else null
                stepSoon()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (stopping || !ProtectionChecks.guardCanRun(this)) {
            stopGuard()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopping = true
        handler.removeCallbacks(nextStep)
        handler.removeCallbacks(checkPermissions)
        if (::overlay.isInitialized) overlay.hide()
        runCatching { unregisterReceiver(screenChanges) }
        runCatching { getSystemService(AppOpsManager::class.java)?.stopWatchingMode(opsChanged) }
        super.onDestroy()
    }

    private fun becameActive() {
        dreaming = false
        activeSinceMs = clock.elapsedMs()
    }

    /** A look at the next turn of the main loop, replacing any look already planned. */
    private fun stepSoon() {
        handler.removeCallbacks(nextStep)
        handler.post(nextStep)
    }

    /** One look: what the decider says now, carried out; the next look scheduled when it asks for one. */
    private fun step() {
        if (stopping || !::decider.isInitialized) return
        handler.removeCallbacks(nextStep)
        val now = clock.elapsedMs()
        val screenOn = power.isInteractive
        // The screen came on since the last look (its broadcast may still be on its way).
        if (screenOn && !interactive) becameActive()
        interactive = screenOn
        val visible = appContainer.appVisibility.state.value
        val needsFront = enforced && screenOn && !dreaming && !visible.resumed
        // About to act: only with both permissions (one may have been taken back without an app-op change,
        // `pm revoke`, and without usage access UsageStats answers with nothing rather than an error).
        if (needsFront && !ProtectionChecks.guardCanRun(this)) {
            stopGuard()
            return
        }
        val input = GuardInput(
            nowMs = now,
            enforced = enforced,
            enforcedSinceMs = enforcedSinceMs,
            appResumed = visible.resumed,
            appLeftAtMs = visible.leftAtMs,
            screenOn = screenOn,
            front = if (needsFront) probe.front() else null,
            dreaming = dreaming,
            activeSinceMs = activeSinceMs,
        )
        val out = decider.step(input)
        if (out.hideOverlay) overlay.hide()
        if (out.showOverlay) overlay.show(timing.overlayTimeoutMs, coverDetail)
        if (out.launchApp) bringBack()
        out.returned?.let(::record)
        out.nextCheckMs?.let { handler.postDelayed(nextStep, it) }
    }

    /**
     * The probe's first look can read hours of usage events. When enforcement starts while Shasha is in front,
     * that reading is done now, off the main thread: the look when Shasha leaves then reads only what came since.
     */
    private fun seedProbe() {
        val probe = probe
        lifecycleScope.launch(Dispatchers.IO) { probe.front() }
    }

    /** Both app-ops watched: taking one back in the TV's settings or with adb stops the guard at once. */
    private fun watchPermissions() {
        val ops = getSystemService(AppOpsManager::class.java) ?: return
        try {
            ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opsChanged)
            ops.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, opsChanged)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot watch the guard's permissions", e)
        }
    }

    /** Its permissions are gone: the guard stops rather than act without them («حالة الحماية» shows it stopped). */
    private fun stopGuard() {
        if (stopping) return
        stopping = true
        Log.i(TAG, "Stopping: display over other apps or usage access is no longer granted")
        handler.removeCallbacks(nextStep)
        if (::overlay.isInitialized) overlay.hide()
        stopSelf()
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

        /** Stops a guard that runs without its permissions (it also stops itself when it notices). */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, GuardService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not stop the guard", e)
            }
        }
    }
}

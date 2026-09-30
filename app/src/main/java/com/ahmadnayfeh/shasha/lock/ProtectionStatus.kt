package com.ahmadnayfeh.shasha.lock

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

/** The official YouTube app for TVs (SPEC §4: optionally disabled by adb-setup). */
enum class YouTubeAppState { NOT_INSTALLED, ENABLED, DISABLED }

/**
 * What «حالة الحماية» shows (SPEC §4, §7). The first two are what the guard needs; the others are
 * reported so the owner sees everything adb-setup should have done.
 */
data class ProtectionStatus(
    /** SYSTEM_ALERT_WINDOW: cover another app, and start Shasha from the background. */
    val overlay: Boolean,
    /** GET_USAGE_STATS: see which app is in front. */
    val usageAccess: Boolean,
    /** REQUEST_INSTALL_PACKAGES: the updater may open the system installer (phase 7). */
    val installPackages: Boolean,
    /** The guard service runs in this process right now. */
    val guardRunning: Boolean,
    val youtube: YouTubeAppState,
    /**
     * The TV sets its date and time from the network. Off, whoever reaches the TV's settings can move the
     * clock: the daily quota restarts with a date set forward, bedtime opens with a time set back.
     */
    val autoTime: Boolean = true,
) {
    /** The guard can work: both of its permissions are granted. */
    val guardCanRun: Boolean get() = overlay && usageAccess

    /** The guard is on and able to act. */
    val guardActive: Boolean get() = guardRunning && guardCanRun

    /** How many required items are missing (the official YouTube app and the automatic time are advice). */
    val missing: Int get() = listOf(overlay, usageAccess, installPackages, guardActive).count { !it }

    /** Advice not followed: the official YouTube app still enabled, the TV's clock set by hand. */
    val warnings: Int get() = listOf(youtube == YouTubeAppState.ENABLED, !autoTime).count { it }
}

/** Reads each item of [ProtectionStatus] from the system (cheap: a few binder calls, no disk). */
object ProtectionChecks {
    const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

    fun read(context: Context, guardRunning: Boolean) = ProtectionStatus(
        overlay = canDrawOverlays(context),
        usageAccess = hasUsageAccess(context),
        installPackages = canRequestInstalls(context),
        guardRunning = guardRunning,
        youtube = youtubeState(context),
        autoTime = autoTime(context),
    )

    /** Settings.Global.AUTO_TIME (on unless someone turned it off; unreadable counts as on). */
    fun autoTime(context: Context): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) != 0 }.getOrDefault(true)

    fun guardCanRun(context: Context): Boolean = canDrawOverlays(context) && hasUsageAccess(context)

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Usage access is an app-op: `appops set <pkg> GET_USAGE_STATS allow` makes it ALLOWED. In its default mode
     * the PACKAGE_USAGE_STATS permission decides (granted to apps holding it by role or `pm grant`).
     */
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A read of the mode, not an access to note: the "unsafe" (unchecked) check is the right one.
                @Suppress("DEPRECATION")
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        } catch (e: RuntimeException) {
            return false
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** REQUEST_INSTALL_PACKAGES is declared (the system throws for apps that do not), so this is the app-op. */
    fun canRequestInstalls(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (e: RuntimeException) {
        false
    }

    fun youtubeState(context: Context): YouTubeAppState {
        val pm = context.packageManager
        val enabled = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(YOUTUBE_TV_PACKAGE, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            return YouTubeAppState.NOT_INSTALLED
        }
        val setting = try {
            pm.getApplicationEnabledSetting(YOUTUBE_TV_PACKAGE)
        } catch (e: IllegalArgumentException) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val disabled = when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> true
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> false
            else -> !enabled
        }
        return if (disabled) YouTubeAppState.DISABLED else YouTubeAppState.ENABLED
    }
}

/** The adb commands «حالة الحماية» shows for each missing item (the same ones the Shasha-Setup installer runs). */
object ProtectionCommands {
    fun overlay(packageName: String) = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"

    fun usageAccess(packageName: String) = "adb shell appops set $packageName GET_USAGE_STATS allow"

    fun installPackages(packageName: String) = "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow"

    fun startApp(packageName: String) = "adb shell am start -n $packageName/.MainActivity"

    /** The owner's emergency exit: the guard stops until Shasha is opened again (a reboot does not restart it). */
    fun forceStop(packageName: String) = "adb shell am force-stop $packageName"

    val disableYouTube = "adb shell pm disable-user --user 0 ${ProtectionChecks.YOUTUBE_TV_PACKAGE}"

    val enableYouTube = "adb shell pm enable ${ProtectionChecks.YOUTUBE_TV_PACKAGE}"

    /** Network time back on (also in the TV's settings: System → Date & time → Automatic). */
    val autoTime = "adb shell settings put global auto_time 1"
}

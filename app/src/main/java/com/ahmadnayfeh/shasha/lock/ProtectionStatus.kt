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
    /**
     * REQUEST_INSTALL_PACKAGES: the updater may open the system installer (phase 7). Below Android 8 (Fire OS 6) no such
     * per-app permission exists: the TV-wide «Unknown sources» setting decides ([unknownSources]).
     */
    val installPackages: Boolean,
    /** The guard service runs in this process right now. */
    val guardRunning: Boolean,
    val youtube: YouTubeAppState,
    /**
     * The TV sets its date and time from the network. Off, whoever reaches the TV's settings can move the
     * clock: the daily quota restarts with a date set forward, bedtime opens with a time set back.
     */
    val autoTime: Boolean = true,
    /** Which official YouTube app [youtube] is about: Google's app for Android TV, or YouTube for Fire TV. */
    val youtubePackage: String = ProtectionChecks.YOUTUBE_TV_PACKAGE,
    /** Android 7.1 or older: [installPackages] is the TV-wide «Unknown sources» setting, not a permission of this app. */
    val unknownSources: Boolean = false,
    /** An Amazon Fire TV: Amazon does not officially support covering other apps, so the guard is tried, not promised. */
    val fireTv: Boolean = false,
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
    /** The official YouTube app on Android TV and Google TV. */
    const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

    /** The official YouTube app on Amazon Fire TV («YouTube for Fire TV»). */
    const val YOUTUBE_FIRE_TV_PACKAGE = "com.amazon.firetv.youtube"

    /** Every Fire TV declares it (Amazon's documented way to tell a Fire TV). */
    const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

    fun read(context: Context, guardRunning: Boolean): ProtectionStatus {
        val (youtubePackage, youtube) = youtubeApp(context)
        return ProtectionStatus(
            overlay = canDrawOverlays(context),
            usageAccess = hasUsageAccess(context),
            installPackages = canRequestInstalls(context),
            guardRunning = guardRunning,
            youtube = youtube,
            autoTime = autoTime(context),
            youtubePackage = youtubePackage,
            unknownSources = Build.VERSION.SDK_INT < Build.VERSION_CODES.O,
            fireTv = isFireTv(context),
        )
    }

    fun isFireTv(context: Context): Boolean = runCatching { context.packageManager.hasSystemFeature(FIRE_TV_FEATURE) }.getOrDefault(false)

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

    /**
     * REQUEST_INSTALL_PACKAGES is declared (the system throws for apps that do not), so from Android 8 this is the app-op.
     * Before it (Fire OS 6, Android 7.1) the TV-wide «Unknown sources» setting lets every app open the system installer.
     */
    fun canRequestInstalls(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) != 0
        }
    } catch (e: RuntimeException) {
        false
    }

    /**
     * The official YouTube app this TV has and its state: one that is enabled first (Google's or Fire TV's), then a disabled
     * one; [YOUTUBE_TV_PACKAGE] when neither is installed.
     */
    fun youtubeApp(context: Context): Pair<String, YouTubeAppState> {
        val found = listOf(YOUTUBE_TV_PACKAGE, YOUTUBE_FIRE_TV_PACKAGE).map { it to youtubeState(context, it) }
        return found.firstOrNull { it.second == YouTubeAppState.ENABLED }
            ?: found.firstOrNull { it.second == YouTubeAppState.DISABLED }
            ?: (YOUTUBE_TV_PACKAGE to YouTubeAppState.NOT_INSTALLED)
    }

    fun youtubeState(context: Context, packageName: String = YOUTUBE_TV_PACKAGE): YouTubeAppState {
        val pm = context.packageManager
        val enabled = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0).enabled
        } catch (e: PackageManager.NameNotFoundException) {
            return YouTubeAppState.NOT_INSTALLED
        }
        val setting = try {
            pm.getApplicationEnabledSetting(packageName)
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

    /** From Android 8 the app-op; before it (Fire OS 6) the TV-wide «Unknown sources» setting, the only switch there is. */
    fun installPackages(packageName: String, unknownSources: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O) =
        if (unknownSources) {
            "adb shell settings put secure install_non_market_apps 1"
        } else {
            "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow"
        }

    fun startApp(packageName: String) = "adb shell am start -n $packageName/.MainActivity"

    /** The owner's emergency exit: the guard stops until Shasha is opened again (a reboot does not restart it). */
    fun forceStop(packageName: String) = "adb shell am force-stop $packageName"

    /** [youtubePackage]: Google's app on Android TV, YouTube for Fire TV on Fire TV ([ProtectionStatus.youtubePackage]). */
    fun disableYouTube(youtubePackage: String = ProtectionChecks.YOUTUBE_TV_PACKAGE) = "adb shell pm disable-user --user 0 $youtubePackage"

    fun enableYouTube(youtubePackage: String = ProtectionChecks.YOUTUBE_TV_PACKAGE) = "adb shell pm enable $youtubePackage"

    /** Network time back on (also in the TV's settings: System → Date & time → Automatic). */
    val autoTime = "adb shell settings put global auto_time 1"
}

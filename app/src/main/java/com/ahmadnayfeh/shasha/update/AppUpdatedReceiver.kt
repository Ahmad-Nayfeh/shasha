package com.ahmadnayfeh.shasha.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.ahmadnayfeh.shasha.MainActivity

/**
 * The note that «شاشة» started an update itself ([UpdateInstaller.install] arms it just before the system takes over),
 * kept on disk because the update stops this process. The new version reads it in [AppUpdatedReceiver]: only an update
 * asked for in the app reopens the app (the installer kit's reinstall starts the app on its own, and nobody wants it
 * to pop up over a video because of some other package change). The note expires, so one left by an update that never
 * happened (cancelled, failed) does not open the app days later.
 */
class RelaunchAfterUpdate(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Written synchronously (`commit = true`): the process may be stopped right after the system takes the APK. */
    fun arm() {
        prefs.edit(commit = true) { putLong(KEY_ARMED_AT, now()) }
    }

    fun disarm() {
        prefs.edit(commit = true) { remove(KEY_ARMED_AT) }
    }

    /** True once, for an update armed within [VALID_MS]; the note is gone either way. */
    fun take(): Boolean {
        val armedAt = prefs.getLong(KEY_ARMED_AT, NOT_ARMED)
        if (armedAt == NOT_ARMED) return false
        disarm()
        return now() - armedAt in 0..VALID_MS
    }

    companion object {
        private const val FILE = "update_relaunch"
        private const val KEY_ARMED_AT = "armed_at"
        private const val NOT_ARMED = 0L

        /** Long enough for the system's confirmation (Android before 12) to be answered. */
        const val VALID_MS = 10 * 60_000L
    }
}

/**
 * After an update the app started itself, opens «شاشة» again (ACTION_MY_PACKAGE_REPLACED goes to the new version).
 *
 * Whether Android lets a receiver start an activity: before Android 10 always. From Android 10 the system blocks
 * activities started from the background, except for an app that holds "display over other apps"
 * (SYSTEM_ALERT_WINDOW): the installer kit grants it, and the guard already relies on it to bring the app back (with
 * the same intent). A TV whose system refuses anyway simply stays on its home screen, nothing fails, and the viewer
 * opens «شاشة» from there, as after any update. A child's profile is brought back by the guard in any case.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!RelaunchAfterUpdate(context).take()) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(open)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open Shasha after the update", e)
        }
    }

    private companion object {
        const val TAG = "AppUpdated"
    }
}

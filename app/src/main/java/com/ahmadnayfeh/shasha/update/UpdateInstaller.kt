package com.ahmadnayfeh.shasha.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.lock.ProtectionChecks
import com.ahmadnayfeh.shasha.lock.ProtectionCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What the system installer reports about one session ([InstallStatusReceiver]). */
sealed interface InstallEvent {
    val sessionId: Int

    /** The system wants the viewer's confirmation: [intent] opens its dialog (never a silent update, SPEC §6). */
    data class Confirm(override val sessionId: Int, val intent: Intent) : InstallEvent

    /** Installed (rarely seen: the update replaces this process). */
    data class Installed(override val sessionId: Int) : InstallEvent

    /** The viewer said no in the system's dialog. */
    data class Cancelled(override val sessionId: Int) : InstallEvent

    data class Failed(override val sessionId: Int, val status: Int, val message: String?) : InstallEvent
}

/** Where [InstallStatusReceiver] reports, for the updater screen (AppContainer.installEvents). */
class InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = flow.asSharedFlow()

    fun report(event: InstallEvent) {
        flow.tryEmit(event)
    }
}

/**
 * Hands a verified APK to the system with the PackageInstaller session API. The system shows its own confirmation
 * (USER_ACTION_REQUIRED from Android 12: never a silent update); the outcome comes back to [InstallStatusReceiver].
 * Needs REQUEST_INSTALL_PACKAGES allowed (the app-op adb-setup grants: [installCommand]).
 */
class UpdateInstaller(private val context: Context) {
    fun installAllowed(): Boolean = ProtectionChecks.canRequestInstalls(context)

    /** The one adb command that allows it (`adb shell appops set <pkg> REQUEST_INSTALL_PACKAGES allow`). */
    val installCommand: String get() = ProtectionCommands.installPackages(context.packageName)

    /** Commits [apk] in a new session and returns its id; the system then asks for the viewer's confirmation. */
    suspend fun install(apk: File): Int = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("shasha.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
                    session.fsync(output)
                }
                session.commit(statusReceiver(sessionId))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        sessionId
    }

    /** A session the viewer never confirmed (the next try opens a new one). */
    fun abandon(sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }

    private fun statusReceiver(sessionId: Int): IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java).setPackage(context.packageName)
        // Mutable: the system adds the status extras to it.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}

/** The system installer's answers (not exported: only the system's PendingIntent reaches it). */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val event = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { InstallEvent.Confirm(sessionId, it) }
                    ?: InstallEvent.Failed(sessionId, status, message)
            PackageInstaller.STATUS_SUCCESS -> InstallEvent.Installed(sessionId)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvent.Cancelled(sessionId)
            else -> InstallEvent.Failed(sessionId, status, message)
        }
        context.appContainer.installEvents.report(event)
    }
}

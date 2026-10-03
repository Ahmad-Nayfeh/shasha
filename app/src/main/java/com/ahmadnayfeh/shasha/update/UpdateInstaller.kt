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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What the system installer reports about one session ([InstallStatusReceiver]). */
sealed interface InstallEvent {
    val sessionId: Int

    /**
     * The system wants the viewer's confirmation after all: [intent] opens its dialog. That is every Android before 12,
     * and an update the system does not let go through silently (see [UpdateInstaller]).
     */
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
 * Hands a verified APK to the system with the PackageInstaller session API; the outcome comes back to
 * [InstallStatusReceiver].
 *
 * Silent where Android allows it. From Android 12 an app that updates itself may install without asking when it asks for
 * that ([PackageInstaller.SessionParams.setRequireUserAction] with USER_ACTION_NOT_REQUIRED), declares
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and targets a recent SDK; REQUEST_INSTALL_PACKAGES must be allowed too (the
 * Shasha-Setup kit grants that app-op). The system then installs without a question, which stops this process, and
 * [AppUpdatedReceiver] opens the new version. Everywhere else (Android 7 to 11, or when the system still wants an
 * answer) the status is STATUS_PENDING_USER_ACTION, and [InstallEvent.Confirm] carries the system's own
 * confirmation: that fallback is always handled.
 *
 * Every call here is safe from API 25: what is newer is guarded by the SDK level ([installAllowed] from 26,
 * `setInstallReason` from 26, `setRequireUserAction` from 31).
 */
class UpdateInstaller(
    private val context: Context,
    private val relaunch: RelaunchAfterUpdate = RelaunchAfterUpdate(context),
) {
    /**
     * Whether this app may hand an APK to the system installer: the per-app "install apps" switch (API 26+), which the
     * kit turns on. Before Android 8 there is no such switch: the system's own confirmation is the only gate.
     */
    fun installAllowed(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Commits [apk] in a new session and returns its id. Silent or not (see above), [relaunch] is armed first: the
     * update stops this process, and the new version must know that it should open itself.
     */
    suspend fun install(apk: File): Int = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("shasha.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
                    session.fsync(output)
                }
                relaunch.arm()
                session.commit(statusReceiver(sessionId))
            }
        } catch (e: Exception) {
            relaunch.disarm()
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

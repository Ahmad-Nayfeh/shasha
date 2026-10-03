package com.ahmadnayfeh.shasha.mobile.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.AppLocale
import com.ahmadnayfeh.shasha.mobile.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What the system installer reports about one session ([InstallStatusReceiver]). */
sealed interface InstallEvent {
    val sessionId: Int

    /** The system wants the viewer's confirmation: [intent] opens its dialog. */
    data class Confirm(override val sessionId: Int, val intent: Intent) : InstallEvent

    /** Installed (rarely seen: the update replaces this process). */
    data class Installed(override val sessionId: Int) : InstallEvent

    /** The viewer said no in the system's dialog. */
    data class Cancelled(override val sessionId: Int) : InstallEvent

    data class Failed(override val sessionId: Int, val status: Int, val message: String?) : InstallEvent
}

/** Where [InstallStatusReceiver] reports, for the update screen ([com.ahmadnayfeh.shasha.mobile.AppContainer.installEvents]). */
class InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = flow.asSharedFlow()

    /** Whether a screen is listening; if not, a confirmation has to reach the viewer another way (a notification). */
    val hasListeners: Boolean get() = flow.subscriptionCount.value > 0

    fun report(event: InstallEvent) {
        flow.tryEmit(event)
    }
}

/**
 * Hands a verified APK to the system with the PackageInstaller session API. From Android 12, an update of an app this app
 * installed before needs no confirmation (USER_ACTION_NOT_REQUIRED, with the UPDATE_PACKAGES_WITHOUT_USER_ACTION
 * permission); otherwise the system shows its own dialog (the first update of an app installed another way). The outcome
 * comes back to [InstallStatusReceiver].
 */
class UpdateInstaller(private val context: Context) {
    /** Whether the viewer allowed this app to install apps ("Install unknown apps"). */
    fun installAllowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system's page where the viewer allows it. */
    fun permissionSettings(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Commits [apk] in a new session and returns its id. */
    suspend fun install(apk: File): Int = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("shasha-mobile.apk", 0, apk.length()).use { output ->
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
        val container = context.container
        if (event is InstallEvent.Confirm && !container.installEvents.hasListeners) {
            // The viewer left the update screen: the system's dialog can only be opened from a notification.
            notifyConfirmation(context, event.intent)
        } else {
            container.installEvents.report(event)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyConfirmation(context: Context, confirm: Intent) {
        val texts = AppLocale.localize(context, context.container.latestSettings().language)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, texts.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 2, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(texts.getString(R.string.update_notif_title))
            .setContentText(texts.getString(R.string.update_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 4102
    }
}

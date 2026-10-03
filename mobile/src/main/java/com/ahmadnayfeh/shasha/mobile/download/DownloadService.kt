package com.ahmadnayfeh.shasha.mobile.download

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ahmadnayfeh.shasha.mobile.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads run and shows their progress notification. The work itself belongs to the
 * [DownloadManager] (the app's); this service starts with the first download of a queue and stops when it is empty.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // The type constant is from API 29; before that ServiceCompat ignores the type, which is what is wanted.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = applicationContext.container
        val manager = container.downloads
        val notifier = container.downloadNotifier
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra(EXTRA_ID, -1)
            if (id >= 0) container.appScope.launch { manager.cancel(id) }
        }
        // Whoever started the service promised a foreground notification within seconds.
        ServiceCompat.startForeground(
            this,
            DownloadNotifier.NOTIFICATION_ID,
            notifier.progress(manager.active.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watching?.isActive != true) {
            watching = scope.launch {
                val updates = launch {
                    var lastAt = 0L
                    var lastPercent = -2
                    manager.active.collect { active ->
                        val percent = active?.let { DownloadNotifier.percent(it.fraction) } ?: -1
                        val at = SystemClock.elapsedRealtime()
                        // A progress bar that moves a few times a second only drains the battery.
                        if (percent != lastPercent && at - lastAt >= UPDATE_EVERY_MS) {
                            lastAt = at
                            lastPercent = percent
                            notifier.update(active)
                        }
                    }
                }
                manager.busy.first { !it }
                updates.cancel()
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * From Android 15 the system ends a data-sync foreground service after about six hours in a day and expects it to stop
     * (an app that does not is crashed). The queue itself goes on while the process lives; the next start of the app takes
     * it up again ([DownloadManager.recover]).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.ahmadnayfeh.shasha.mobile.download.CANCEL"
        const val EXTRA_ID = "download_id"
        private const val UPDATE_EVERY_MS = 700L

        /** Starts the service (from the foreground, or right after the app was started by the viewer). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed to start a foreground service from the background: the download still runs while the
                // process lives, only without its notification.
            }
        }
    }
}

package com.ahmadnayfeh.shasha.mobile.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ahmadnayfeh.shasha.mobile.MainActivity
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.AppLocale
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity

/**
 * The download notifications: one with a progress bar while a download runs (it is the foreground service's, with a
 * «Cancel» button), and one per finished or failed download. Texts are read in the app's own language.
 */
class DownloadNotifier(private val context: Context, private val language: () -> AppLanguage) : DownloadNotifications {

    private fun texts(): Context = AppLocale.localize(context, language())

    /** Creates the channels (idempotent): called when the app starts. */
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val t = texts()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, t.getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, t.getString(R.string.notif_channel_downloads_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The foreground notification: the running download's title and progress ([active] null: just starting). */
    fun progress(active: ActiveDownload?): Notification {
        val t = texts()
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloads())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active == null) {
            return builder.setContentTitle(t.getString(R.string.app_name)).setContentText(t.getString(R.string.download_preparing))
                .setProgress(0, 0, true).build()
        }
        builder.setContentTitle(active.title.ifBlank { t.getString(R.string.app_name) })
        when (active.stage) {
            DownloadStage.PREPARING -> builder.setContentText(t.getString(R.string.download_preparing)).setProgress(0, 0, true)
            DownloadStage.MERGING -> builder.setContentText(t.getString(R.string.download_merging)).setProgress(PERCENT, percent(active.fraction), false)
            DownloadStage.DOWNLOADING ->
                if (active.fraction < 0) {
                    builder.setContentText(t.getString(R.string.download_downloading)).setProgress(0, 0, true)
                } else {
                    builder.setContentText(t.getString(R.string.download_downloading_percent, percent(active.fraction).toString()))
                        .setProgress(PERCENT, percent(active.fraction), false)
                }
        }
        builder.addAction(0, t.getString(R.string.action_cancel), cancelIntent(active.id))
        return builder.build()
    }

    /** Refreshes the foreground notification (the service's id). */
    @SuppressLint("MissingPermission") // without the notification permission the service still runs, silently
    fun update(active: ActiveDownload?) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, progress(active))
    }

    @SuppressLint("MissingPermission")
    override fun completed(download: DownloadEntity) {
        val t = texts()
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(download.title.ifBlank { t.getString(R.string.app_name) })
            .setContentText(t.getString(R.string.download_done))
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(DONE_BASE_ID + download.id.toInt(), notification) }
    }

    @SuppressLint("MissingPermission")
    override fun failed(download: DownloadEntity) {
        val t = texts()
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(download.title.ifBlank { t.getString(R.string.app_name) })
            .setContentText(t.getString(R.string.download_failed))
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(DONE_BASE_ID + download.id.toInt(), notification) }
    }

    private fun openDownloads(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(id: Long): PendingIntent {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
            .putExtra(DownloadService.EXTRA_ID, id)
        return PendingIntent.getService(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_DONE = "downloads_done"
        const val NOTIFICATION_ID = 4101
        private const val DONE_BASE_ID = 5000
        private const val PERCENT = 100

        fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * PERCENT).toInt()
    }
}

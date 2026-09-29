package com.ahmadnayfeh.shasha.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the guard after the TV boots and after Shasha is updated ([GuardService.start]): a locked video,
 * or "time up", survives a power cut, and the guard then brings Shasha back over the launcher by itself.
 * Both broadcasts allow a foreground service to start from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> GuardService.start(context)
        }
    }
}

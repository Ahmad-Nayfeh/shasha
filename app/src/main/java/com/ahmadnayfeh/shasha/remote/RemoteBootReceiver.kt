package com.ahmadnayfeh.shasha.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the phone remote after the TV boots and after Shasha is updated (both broadcasts allow a
 * foreground service to start from the background): a phone can then bring Shasha up with a link.
 */
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // The settings are read asynchronously: keep the process alive until the start is decided.
                val pending = goAsync()
                RemoteService.startIfWanted(context) { pending.finish() }
            }
        }
    }
}

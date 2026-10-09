package com.ahmadnayfeh.shasha.mobile.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ahmadnayfeh.shasha.mobile.MainActivity
import com.ahmadnayfeh.shasha.mobile.container

/**
 * Background playback: wraps the app's one player ([PlayerController.player]) in a media session, which gives the
 * media notification (play/pause, a seek bar), the lock-screen controls and the Bluetooth and headset buttons. Media3
 * promotes this service to the foreground while the player plays, and takes the notification down when it stops.
 *
 * The session is added to the service here, in [onCreate] ([addSession]). Media3 adds a session by itself only when a
 * MediaController connects to the service, and the app never makes one (the screens use the player directly): without
 * this call the service had no notification and never became a foreground service, so about a minute after the app was
 * left (the screen off, another app) Android stopped it as an idle background service, the app's process was cached and
 * frozen, and the video stopped until the viewer came back and pressed «Try again».
 *
 * The player itself lives in the app, not here: the screen and this service share it. Releasing the session never
 * releases the player.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = applicationContext.container.player.player
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val created = MediaSession.Builder(this, player).setId(SESSION_ID).setSessionActivity(open).build()
        session = created
        // The media notification, and with it the foreground service, follow the player from now on (see the class comment).
        addSession(created)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app was swiped away from the recent apps: a player that is not playing has nothing to keep alive. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val SESSION_ID = "shasha_mobile"

        /** Called from the foreground (a screen asked for playback). */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, PlaybackService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed to start a service from the background: the screen is the one asking, so this is rare.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}

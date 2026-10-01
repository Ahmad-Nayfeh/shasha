package com.ahmadnayfeh.shasha.remote

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.player.ActivePlayer
import com.ahmadnayfeh.shasha.player.NowPlaying
import com.ahmadnayfeh.shasha.player.PlayerCommand
import com.ahmadnayfeh.shasha.player.PlayerRefusal
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Not a check by itself: the TV's real rules behind the phone page, for the browser checklist (Playwright). Skipped
 * unless SHASHA_QA_DIR names a directory. It serves the page on 127.0.0.1 (SHASHA_QA_PORT, else 8765) with a real
 * [RemoteRuntime] over [RemoteTestEnv] (PolicyGate, the lock, the phone's PIN count, the settings stores), writes the
 * pairing link to "ready" in that directory, and runs what a script writes to "cmd" (one command per line, the answers
 * go to "cmd.out", see [command]) until a "stop" file appears, 30 minutes at most.
 *
 * The "TV" plays what the phone sends: a player that runs in real time is attached, and the lock is taken the way the
 * player screen takes it ([com.ahmadnayfeh.shasha.lock.LockController.onVideoStarted]). Parent PIN: [PIN].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhonePageQaServer {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun serveThePageForTheBrowserChecklist() {
        val dir = System.getenv("SHASHA_QA_DIR")?.let(::File)
        assumeTrue("SHASHA_QA_DIR is not set: nothing to serve", dir != null && dir.isDirectory)
        val env = remoteEnv(tmp.root)
        try {
            runBlocking { serve(env, dir!!) }
        } finally {
            env.close()
        }
    }

    private lateinit var env: RemoteTestEnv
    private lateinit var runtime: RemoteRuntime
    private lateinit var controller: TvRemoteController
    private val display = QaDisplay()

    private suspend fun serve(env: RemoteTestEnv, dir: File) {
        this.env = env
        listOf("stop", "ready", "cmd", "cmd.run", "cmd.out").forEach { File(dir, it).delete() }
        seed()
        controller = env.newController(
            env.phonePin,
            display = display,
            restartRemote = { runtime.restart() },
            restartDelayMs = PhoneSettings.RESTART_DELAY_MS,
            portAvailable = { runtime.portAvailable(it) },
            movingTo = { runtime.movingTo.value },
        )
        val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
        runtime = RemoteRuntime(env.remoteSettings, controller, SilentAnnouncer, findAddress = { loopback })
        System.getenv("SHASHA_QA_PORT")?.toIntOrNull()?.let { env.remoteSettings.rememberPort(it) }
        check(runtime.refresh() is RemoteState.Running) { "The server did not start" }
        File(dir, "ready").writeText(runtime.pairingUrl() + "\n")
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        while (!File(dir, "stop").exists() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val cmd = File(dir, "cmd")
            val run = File(dir, "cmd.run")
            if (cmd.exists() && cmd.renameTo(run)) {
                val answers = run.readLines().filter { it.isNotBlank() }.map { line ->
                    try {
                        command(line.trim().split(Regex("\\s+")))
                    } catch (e: Exception) {
                        "ERROR ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                run.delete()
                File(dir, "cmd.out.tmp").writeText(answers.joinToString("\n") + "\n")
                File(dir, "cmd.out.tmp").renameTo(File(dir, "cmd.out"))
            }
            delay(50)
        }
        runtime.stop()
    }

    /** Arabic titles and a few channels, so the page shows something like a real TV. */
    private suspend fun seed() {
        val videos = listOf(
            clip("kidsvideo01", channelId = ALLOWED_CHANNEL, title = "حكاية الأرنب والسلحفاة", durationSeconds = 534),
            clip("kidsvideo02", channelId = ALLOWED_CHANNEL, title = "لماذا السماء زرقاء؟", durationSeconds = 402),
            clip("docvideo001", channelId = OTHER_CHANNEL, title = "رحلة إلى أعماق المحيط الهادئ", durationSeconds = 2710),
            clip("docvideo002", channelId = OTHER_CHANNEL, title = "10 Facts About Black Holes!", durationSeconds = 612),
            clip("livevideo01", channelId = OTHER_CHANNEL, title = "بث مباشر: الأرض من الفضاء", durationSeconds = null, live = true),
        )
        videos.forEach { env.source.videos[it.videoId] = it }
        env.source.searchResults = videos
        env.source.channels[ALLOWED_CHANNEL] = ChannelItem(ALLOWED_CHANNEL, "قناة مسموحة", null, null)
        env.source.channels[OTHER_CHANNEL] = ChannelItem(OTHER_CHANNEL, "عالم الوثائقيات", null, null)
        env.source.handles["@docs"] = OTHER_CHANNEL
        env.subscriptions.add(ChannelItem(OTHER_CHANNEL, "عالم الوثائقيات", null, 1_200_000))
    }

    /**
     * What the script asks of the TV side (answers are one line each):
     * `status` · `player` (the commands the player got) · `used <profile> <seconds>` (watched time today) ·
     * `serious on|off` · `adultEntry on|off` · `extra <m,m,…>` (the TV's own Settings) · `quota <profile> <minutes|none>`
     * (the TV's profile editor) · `switch <profile>` (the TV's picker, no PIN: a child) · `revoke` (every phone's parent
     * session ends) · `tvpin <pin>` (a PIN typed on the TV) · `phonepin` (the phone's PIN count) · `serverdown <ms>` (the
     * TV's network drops for a moment) · `endvideo` (the video ends by itself).
     */
    private suspend fun command(args: List<String>): String = when (args[0]) {
        "status" -> {
            val s = controller.status()
            "profile=${s.profileName} kind=${s.profileKind} locked=${s.videoLocked} serious=${s.seriousMode} state=${s.sessionState} " +
                "left=${s.timeLeftMinutes} playing=${s.nowPlaying?.videoId} rev=${s.rev} port=${(runtime.state.value as? RemoteState.Running)?.port}"
        }
        "player" -> display.player?.commands?.joinToString(",").orEmpty().ifEmpty { "none" }
        "used" -> {
            env.used(args[1], args[2].toLong())
            "ok"
        }
        "serious" -> {
            env.behavior.setSeriousMode(args[1] == "on")
            "ok"
        }
        "adultEntry" -> {
            env.behavior.setAdultEntryPin(args[1] == "on")
            "ok"
        }
        "extra" -> {
            env.behavior.setExtraMinutes(args[1].split(',').map { it.toInt() })
            "ok"
        }
        "quota" -> {
            env.profiles.update(args[1]) { it.copy(dailyQuotaMinutes = args[2].toIntOrNull()) }
            env.session.refresh()
            "ok"
        }
        "switch" -> env.gate.switchProfile(args[1], pin = null).toString()
        "revoke" -> {
            runtime.revokeParentSessions()
            "ok"
        }
        "tvpin" -> env.parentPin.verify(args[1]).toString()
        "phonepin" -> env.phonePin.status().toString()
        "serverdown" -> {
            runtime.stop()
            delay(args[1].toLong())
            runtime.refresh().toString()
        }
        "endvideo" -> {
            val p = display.player ?: return "none"
            env.lock.onVideoEnded(env.current().id, p.videoId, nextVideoId = null)
            p.leave()
            "ok"
        }
        else -> "unknown command ${args[0]}"
    }

    /** The TV's screens: a player for a video (locked as the player screen would), anything else is simply shown. */
    private inner class QaDisplay : RemoteDisplay {
        @Volatile var player: QaPlayer? = null

        override suspend fun show(destination: Screen, profileId: String): ShowResult {
            if (destination is Screen.Player) {
                val video = env.source.videos[destination.videoId]
                player?.leave()
                val next = QaPlayer(destination.videoId, video?.title ?: destination.videoId, video?.channelName, video?.durationSeconds?.times(1000), video?.isLive == true)
                next.detach = env.hub.attach(next)
                player = next
                env.lock.onVideoStarted(profileId, destination.videoId, destination.playlistId)
            }
            return ShowResult.SHOWN
        }
    }

    /** A player running in real time: position, play/pause, jumps, leaving. */
    private class QaPlayer(
        override val videoId: String,
        private val title: String,
        private val channel: String?,
        private val durationMs: Long?,
        private val live: Boolean,
    ) : ActivePlayer {
        @Volatile var detach: () -> Unit = {}
        val commands = CopyOnWriteArrayList<String>()
        private var playing = true
        private var base = 0L
        private var since = System.nanoTime()

        private fun position(): Long {
            val p = base + if (playing) (System.nanoTime() - since) / 1_000_000 else 0
            return durationMs?.let { p.coerceIn(0, it) } ?: p
        }

        @Synchronized
        override fun nowPlaying() = NowPlaying(
            videoId = videoId, title = title, channelName = channel, thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
            positionMs = position(), durationMs = if (live) null else durationMs, playing = playing, live = live, ended = false,
        )

        @Synchronized
        override fun perform(command: PlayerCommand): PlayerRefusal? {
            if (command is PlayerCommand.SeekBy && live) return PlayerRefusal.LIVE
            commands += when (command) {
                is PlayerCommand.SeekBy -> "seek${command.millis / 1000}"
                else -> command.toString().lowercase()
            }
            val now = position()
            base = now
            since = System.nanoTime()
            when (command) {
                PlayerCommand.Play -> playing = true
                PlayerCommand.Pause -> playing = false
                PlayerCommand.Toggle -> playing = !playing
                is PlayerCommand.SeekBy -> base = (now + command.millis).coerceIn(0, durationMs ?: Long.MAX_VALUE)
            }
            return null
        }

        override fun leave() {
            commands += "leave"
            detach()
        }
    }

    private object SilentAnnouncer : Announcer {
        override val name: String? = null

        override fun register(port: Int) = Unit

        override fun unregister() = Unit
    }
}

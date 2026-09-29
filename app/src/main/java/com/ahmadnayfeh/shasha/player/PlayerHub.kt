package com.ahmadnayfeh.shasha.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.ahmadnayfeh.shasha.ShashaApp
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the player on screen shows now, for the phone remote's «الآن على الشاشة». */
data class NowPlaying(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val positionMs: Long,
    /** Null for a live stream (its "duration" is only the DVR window) or while unknown. */
    val durationMs: Long?,
    /** Wants to play (buffering included); false when paused or ended. */
    val playing: Boolean,
    val live: Boolean,
    val ended: Boolean,
)

/** A command from the phone for the player on screen (SPEC §5: تشغيل/إيقاف/تقديم). */
sealed interface PlayerCommand {
    data object Play : PlayerCommand

    data object Pause : PlayerCommand

    data object Toggle : PlayerCommand

    /** Relative jump; negative goes back. */
    data class SeekBy(val millis: Long) : PlayerCommand
}

/** Why the player did not carry out a [PlayerCommand]. */
enum class PlayerRefusal {
    /** On its end card: «إعادة التشغيل» on the TV starts it again (and locks it again, SPEC §4). */
    ENDED,

    /** A live stream has no timeline to jump in. */
    LIVE,

    /** A lock screen is in front (the parent PIN pad, the exit phrase): the video waits for it. */
    COVERED,
}

/**
 * The player on screen as the phone remote sees it. Every member is called on the main thread (ExoPlayer
 * lives there).
 */
interface ActivePlayer {
    val videoId: String

    fun nowPlaying(): NowPlaying

    /** Null when done, else why not. */
    fun perform(command: PlayerCommand): PlayerRefusal?

    /**
     * Leaves the player the way «رجوع» does: at once when nothing holds the video; a locked video shows its
     * own way out instead (callers refuse STOP while a video is locked, SPEC §4).
     */
    fun leave()
}

/**
 * The one place that knows which player is on screen (at most one: the TV shows one video at a time). The
 * player screen attaches its player while it exists ([AttachToPlayerHub]); the phone remote reads and
 * drives it. Nothing here keeps a player alive: it detaches when its screen stops or leaves.
 */
class PlayerHub {
    private val _active = MutableStateFlow<ActivePlayer?>(null)

    /** The player on screen, or null (no video, the app in the background, the "time up" screen). */
    val active: StateFlow<ActivePlayer?> = _active.asStateFlow()

    /** Makes [player] the one on screen; the returned function detaches it (only if it is still the one). */
    fun attach(player: ActivePlayer): () -> Unit {
        _active.value = player
        return { _active.compareAndSet(player, null) }
    }
}

/**
 * Attaches [controller]'s player to the app's [PlayerHub] while this composition holds it. [leave] is the
 * player screen's own exit (its lock decides), [covered] whether a lock screen stands in front of the video.
 * Does nothing outside the app (tests with a plain Application).
 */
@Composable
fun AttachToPlayerHub(controller: PlaybackController, video: VideoItem, leave: () -> Unit, covered: () -> Boolean) {
    val hub = (LocalContext.current.applicationContext as? ShashaApp)?.container?.playerHub ?: return
    val onLeave by rememberUpdatedState(leave)
    val isCovered by rememberUpdatedState(covered)
    DisposableEffect(hub, controller, video.videoId) {
        val detach = hub.attach(ControllerPlayer(controller, video, leave = { onLeave() }, covered = { isCovered() }))
        onDispose { detach() }
    }
}

/** [ActivePlayer] over the screen's [PlaybackController]. */
internal class ControllerPlayer(
    private val controller: PlaybackController,
    private val video: VideoItem,
    private val leave: () -> Unit,
    private val covered: () -> Boolean,
) : ActivePlayer {
    override val videoId: String get() = video.videoId

    override fun nowPlaying(): NowPlaying {
        val ui = controller.state.value
        val progress = controller.progress()
        val live = progress.isLive || ui.isLive
        return NowPlaying(
            videoId = video.videoId,
            title = ui.title,
            channelName = ui.channelName,
            thumbnailUrl = Thumbnails.video(video.videoId),
            positionMs = progress.positionMs,
            durationMs = progress.durationMs.takeIf { it > 0 && !live },
            playing = ui.playWhenReady && !ui.isEnded,
            live = live,
            ended = ui.isEnded,
        )
    }

    override fun perform(command: PlayerCommand): PlayerRefusal? {
        val ui = controller.state.value
        if (ui.isEnded) return PlayerRefusal.ENDED
        when (command) {
            PlayerCommand.Pause -> controller.onAction(PlayerAction.Pause)
            PlayerCommand.Play -> {
                if (covered()) return PlayerRefusal.COVERED
                controller.onAction(PlayerAction.Play)
            }
            PlayerCommand.Toggle -> {
                if (!ui.playWhenReady && covered()) return PlayerRefusal.COVERED
                controller.onAction(PlayerAction.TogglePlayPause)
            }
            is PlayerCommand.SeekBy -> {
                val progress = controller.progress()
                if (progress.isLive || ui.isLive) return PlayerRefusal.LIVE
                controller.onAction(PlayerAction.SeekTo((progress.positionMs + command.millis).coerceAtLeast(0)))
            }
        }
        return null
    }

    override fun leave() = leave.invoke()
}

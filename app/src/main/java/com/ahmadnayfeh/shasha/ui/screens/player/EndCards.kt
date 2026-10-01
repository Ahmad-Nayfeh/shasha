package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.LocalBehavior
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.components.ThumbnailBadge
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.delay
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.Accents

/**
 * What the screen shows when a video ends: a calm card with «رجوع» (no related videos, nothing plays by
 * itself), or inside a playlist the next video with a short countdown. BACK leaves ([onBack]).
 *
 * [kidsHome]: a child's video or playlist that ended (its lock is released, SPEC §4) goes back to the
 * child home, by itself after [homeSeconds] or at once with «الرئيسية». [paused]: a lock screen is in front
 * (a child asked to leave during the countdown): nothing counts down, and the focus comes back after.
 * [stillLocked]: the video was skipped to its end, so its lock stays; the card says so («رجوع» then asks
 * for the PIN or the exit challenge).
 */
@Composable
fun EndCard(
    end: PlaybackEnd,
    title: String,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onPlayNext: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    countdownSeconds: Int = LocalBehavior.current.upNextSeconds,
    tickMs: Long = 1_000L,
    paused: Boolean = false,
    kidsHome: (() -> Unit)? = null,
    homeSeconds: Int = LocalBehavior.current.kidsHomeSeconds,
    stillLocked: Boolean = false,
) {
    val home = kidsHome.takeIf { end !is PlaybackEnd.UpNext }
    BackHandler(enabled = !paused) { (home ?: onBack)() }
    val first = remember { FocusRequester() }
    LaunchedEffect(end, paused) {
        if (paused) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.9f))))
            .testTag("end_card"),
        contentAlignment = Alignment.Center,
    ) {
        val heading = stringResource(if (end == PlaybackEnd.PlaylistFinished) R.string.player_playlist_ended_title else R.string.player_ended_title)
        when {
            end is PlaybackEnd.UpNext -> UpNext(end.next, countdownSeconds, tickMs, paused, onBack, onPlayNext, first)
            home != null -> KidsFinished(heading, title, homeSeconds, tickMs, paused, home, onReplay, first)
            else -> Finished(heading = heading, title = title, onBack = onBack, onReplay = onReplay, first = first, stillLocked = stillLocked)
        }
    }
}

/** A child's video (or playlist) is over: a friendly word, then back to the child home by itself. */
@Composable
private fun KidsFinished(
    heading: String,
    title: String,
    seconds: Int,
    tickMs: Long,
    paused: Boolean,
    onHome: () -> Unit,
    onReplay: () -> Unit,
    first: FocusRequester,
) {
    val formats = rememberFormats()
    var left by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (left > 1) {
            delay(tickMs)
            left--
        }
        delay(tickMs)
        onHome()
    }
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EndBadge(ShashaIcons.CheckCircle)
        Spacer(Modifier.height(20.dp))
        Text(heading, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = OsdColors.Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            formats.count(R.array.back_home_in_seconds, left.toLong()),
            style = MaterialTheme.typography.titleMedium,
            color = Accents.overDark(LocalAccent.current).bright,
            modifier = Modifier.testTag("kids_home_countdown"),
        )
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ShashaButton(
                stringResource(R.string.player_kids_home),
                onHome,
                icon = ShashaIcons.Home,
                primary = true,
                modifier = Modifier.focusRequester(first).testTag("end_kids_home"),
            )
            ShashaButton(stringResource(R.string.player_replay), onReplay, icon = ShashaIcons.Replay, modifier = Modifier.testTag("end_replay"))
        }
    }
}

@Composable
private fun EndBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .size(84.dp)
            .background(Accents.overDark(LocalAccent.current).tint(0.16f), CircleShape)
            .border(1.dp, Accents.overDark(LocalAccent.current).tint(0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Accents.overDark(LocalAccent.current).bright, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun Finished(heading: String, title: String, onBack: () -> Unit, onReplay: () -> Unit, first: FocusRequester, stillLocked: Boolean) {
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EndBadge(if (stillLocked) ShashaIcons.Lock else ShashaIcons.CheckCircle)
        Spacer(Modifier.height(20.dp))
        Text(heading, style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = OsdColors.Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (stillLocked) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.player_skipped_still_locked),
                style = MaterialTheme.typography.titleSmall,
                color = OsdColors.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("end_still_locked"),
            )
        }
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ShashaButton(
                stringResource(R.string.action_back),
                onBack,
                icon = ShashaIcons.Back,
                primary = true,
                modifier = Modifier.focusRequester(first).testTag("end_back"),
            )
            ShashaButton(stringResource(R.string.player_replay), onReplay, icon = ShashaIcons.Replay, modifier = Modifier.testTag("end_replay"))
        }
    }
}

@Composable
private fun UpNext(
    next: VideoItem,
    countdownSeconds: Int,
    tickMs: Long,
    paused: Boolean,
    onBack: () -> Unit,
    onPlayNext: (VideoItem) -> Unit,
    first: FocusRequester,
) {
    val formats = rememberFormats()
    var left by remember(next.videoId) { mutableIntStateOf(countdownSeconds) }
    LaunchedEffect(next.videoId, paused) {
        // 0: the parent turned the countdown off; the next video waits for «شغّل الآن».
        if (paused || countdownSeconds <= 0) return@LaunchedEffect
        while (left > 1) {
            delay(tickMs)
            left--
        }
        delay(tickMs)
        onPlayNext(next)
    }
    Column(Modifier.widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.player_up_next), style = MaterialTheme.typography.labelLarge, color = Accents.overDark(LocalAccent.current).bright)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(384.dp).height(216.dp)) {
            val url = remember(next.videoId) { Thumbnails.video(next.videoId) }
            Thumbnail(url, Modifier.fillMaxSize(), RoundedCornerShape(20.dp), logoSize = 72.dp)
            next.durationSeconds?.let {
                ThumbnailBadge(formatDuration(it * 1000), Modifier.align(Alignment.BottomEnd).padding(10.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            next.title,
            style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        next.channelName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = OsdColors.Muted, maxLines = 1) }
        Spacer(Modifier.height(10.dp))
        if (countdownSeconds > 0) {
            Text(
                formats.count(R.array.starts_in_seconds, left.toLong()),
                style = MaterialTheme.typography.titleMedium,
                color = OsdColors.Secondary,
                modifier = Modifier.testTag("up_next_countdown"),
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ShashaButton(
                stringResource(R.string.player_play_now),
                { onPlayNext(next) },
                icon = ShashaIcons.SkipNext,
                primary = true,
                modifier = Modifier.focusRequester(first).testTag("up_next_play"),
            )
            ShashaButton(stringResource(R.string.action_back), onBack, icon = ShashaIcons.Back, modifier = Modifier.testTag("up_next_back"))
        }
    }
}

/** The countdown before the next playlist video (Settings may change it: [LocalBehavior]). */
const val UP_NEXT_SECONDS = 5

/** The countdown before a child whose video ended is back on the child home (Settings may change it). */
const val KIDS_HOME_SECONDS = 10

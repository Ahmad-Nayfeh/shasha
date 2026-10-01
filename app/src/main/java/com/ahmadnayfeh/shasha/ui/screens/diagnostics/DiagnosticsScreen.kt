package com.ahmadnayfeh.shasha.ui.screens.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorIllustration
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorTexts
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import java.util.Locale
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign
import com.ahmadnayfeh.shasha.ui.components.brandLockup

/** Stateful entry point: runs the checks and opens the player. */
@Composable
fun DiagnosticsRoute(onOpen: (videoId: String) -> Unit) {
    val container = LocalContext.current.appContainer
    val vm: DiagnosticsViewModel = viewModel(factory = viewModelFactory { initializer { DiagnosticsViewModel(container.streams) } })
    val items by vm.items.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        items = items,
        extractorVersion = container.videoSource.extractorVersion,
        appVersion = BuildConfig.VERSION_NAME,
        initialFocus = vm.focusedIndex,
        onFocused = { vm.focusedIndex = it },
        onOpen = { onOpen(it.videoId) },
        onRecheck = vm::recheck,
    )
}

/**
 * "فيديوهات تجريبية": the fixed test videos on the right (start side), and the focused one's details
 * on the left. Everything fits one TV screen, no scrolling.
 */
@Composable
fun DiagnosticsScreen(
    items: List<DiagnosticItem>,
    extractorVersion: String,
    appVersion: String,
    onOpen: (TestVideo) -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: Int = 0,
    onFocused: (Int) -> Unit = {},
) {
    var focused by remember { mutableIntStateOf(initialFocus.coerceIn(0, items.lastIndex)) }
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { requesters[focused].requestFocus() } }

    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ShashaColors.Surface, ShashaColors.Background), endY = 700f))
            .padding(ShashaDimens.ScreenPadding)
            .testTag("diagnostics"),
    ) {
        Header(extractorVersion, appVersion, onRecheck)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(392.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEachIndexed { index, item ->
                    DiagnosticRow(
                        item = item,
                        onClick = { onOpen(item.video) },
                        modifier = Modifier
                            .focusRequester(requesters[index])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focused = index
                                    onFocused(index)
                                }
                            }
                            .testTag("diag_row_${item.video.name}"),
                    )
                }
            }
            Spacer(Modifier.width(36.dp))
            val item = items.getOrNull(focused)
            if (item != null) {
                AnimatedContent(
                    targetState = item,
                    contentKey = { it.video to it.status::class },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.weight(1f),
                    label = "preview",
                ) { shown -> Preview(shown) }
            }
        }
    }
}

@Composable
private fun Header(extractorVersion: String, appVersion: String, onRecheck: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(brandLockup()), contentDescription = stringResource(R.string.app_name), modifier = Modifier.height(40.dp))
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .size(width = 1.dp, height = 40.dp)
                .background(ShashaColors.Outline),
        )
        Column {
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.diag_subtitle), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.diag_versions, extractorVersion, appVersion),
            style = MaterialTheme.typography.labelMedium,
            color = ShashaColors.Muted,
        )
        Spacer(Modifier.width(20.dp))
        ShashaButton(
            text = stringResource(R.string.diag_recheck),
            onClick = onRecheck,
            icon = ShashaIcons.Refresh,
            modifier = Modifier.testTag("diag_recheck"),
        )
    }
}

@Composable
private fun DiagnosticRow(item: DiagnosticItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(62.dp),
        shape = RoundedCornerShape(16.dp),
        scale = 1.03f,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(item.status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.video.label), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = (item.status as? CheckStatus.Ready)?.summary?.title ?: stringResource(item.video.about),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = ShashaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            StatusText(item.status)
        }
    }
}

private fun statusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.Waiting -> ShashaColors.Muted
    CheckStatus.Checking -> ShashaColors.Secondary
    is CheckStatus.Ready -> ShashaColors.Primary
    is CheckStatus.Failed -> ShashaColors.Error
}

@Composable
private fun StatusDot(status: CheckStatus) {
    val pulse = if (status == CheckStatus.Checking) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dotAlpha").value
    } else {
        1f
    }
    Box(
        Modifier
            .size(12.dp)
            .alpha(pulse)
            .background(statusColor(status), CircleShape),
    )
}

@Composable
private fun StatusText(status: CheckStatus) {
    val text = when (status) {
        CheckStatus.Waiting -> stringResource(R.string.diag_status_waiting)
        CheckStatus.Checking -> stringResource(R.string.diag_status_checking)
        is CheckStatus.Ready -> readyLabel(status.summary)
        is CheckStatus.Failed -> stringResource(ErrorTexts.short(status.error.kind))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = statusColor(status), maxLines = 1)
}

@Composable
private fun readyLabel(summary: StreamSummary): String {
    val best = summary.bestHeight
    return when {
        summary.isLive -> stringResource(R.string.diag_status_ready_live)
        best != null -> stringResource(R.string.diag_status_ready, best.toString())
        else -> stringResource(R.string.diag_status_ready_plain)
    }
}

/** Details of the focused test video. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(item: DiagnosticItem) {
    val status = item.status
    val summary = (status as? CheckStatus.Ready)?.summary
    Column(Modifier.fillMaxWidth().testTag("diag_preview")) {
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            Thumbnail(
                summary?.thumbnailUrl,
                Modifier.fillMaxSize(),
                RoundedCornerShape(20.dp),
                showLogo = status !is CheckStatus.Failed,
            )
            // Soft shade so the badges stay readable on bright thumbnails.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))),
                        RoundedCornerShape(20.dp),
                    ),
            )
            CaseBadge(item.video, Modifier.align(Alignment.TopStart).padding(12.dp))
            when {
                status is CheckStatus.Failed -> ErrorIllustration(
                    status.error.kind,
                    Modifier.align(Alignment.Center).size(width = 84.dp, height = 90.dp),
                )
                summary != null -> Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ShashaIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            summary?.let { s ->
                val badge = when {
                    s.isLive -> stringResource(R.string.diag_fact_live)
                    s.durationSeconds != null -> formatDuration(s.durationSeconds * 1000)
                    else -> null
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .background(if (s.isLive) ShashaColors.Error else Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        when (status) {
            CheckStatus.Waiting, CheckStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                ShashaLoader(size = 36.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(if (status == CheckStatus.Checking) R.string.diag_status_checking else R.string.diag_status_waiting),
                    style = MaterialTheme.typography.titleMedium,
                    color = ShashaColors.Secondary,
                )
            }
            is CheckStatus.Ready -> {
                // Latin titles keep their reading order but hang from the right edge like the rest.
                Text(
                    status.summary.title,
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    textAlign = StartTextAlign,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                status.summary.channelName?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.summary.bestHeight?.let { Fact(stringResource(R.string.diag_fact_best_quality, it.toString())) }
                    Fact(stringResource(R.string.diag_fact_subtitles, status.summary.subtitleCount.toString()))
                    Fact(stringResource(R.string.diag_fact_elapsed, seconds(status.elapsedMs)))
                }
            }
            is CheckStatus.Failed -> {
                Text(
                    stringResource(ErrorTexts.explanation(status.error.kind)),
                    style = MaterialTheme.typography.titleMedium,
                    color = ShashaColors.Text,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fact(ErrorTexts.technical(status.error.kind, status.error.technicalName), ShashaColors.Error, ltr = true)
                    Fact(stringResource(R.string.diag_fact_elapsed, seconds(status.elapsedMs)))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(item.video.about), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
    }
}

@Composable
private fun CaseBadge(video: TestVideo, modifier: Modifier = Modifier) {
    val accent = when (video) {
        TestVideo.KIDS -> ShashaColors.KidsAccent
        TestVideo.LIVE -> ShashaColors.Error
        TestVideo.DELETED -> ShashaColors.Muted
        else -> ShashaColors.Primary
    }
    Text(
        stringResource(video.label),
        style = MaterialTheme.typography.labelLarge,
        color = ShashaColors.Background,
        modifier = modifier
            .background(accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun Fact(text: String, color: Color = ShashaColors.Text, ltr: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(textDirection = if (ltr) TextDirection.Ltr else TextDirection.Content),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .background(ShashaColors.SurfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, ShashaColors.Outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.lock.GuardService
import com.ahmadnayfeh.shasha.lock.ProtectionChecks
import com.ahmadnayfeh.shasha.lock.ProtectionCommands
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.ReturnTime
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@Composable
fun ProtectionRoute() {
    val context = LocalContext.current
    val container = context.appContainer
    val status by rememberProtectionStatus()
    val returns by container.guardReturnTimes.times.collectAsStateWithLifecycle(emptyList())
    ProtectionScreen(status, returns, context.packageName, container.clock.zone)
}

/**
 * What the system says about the protection right now, read again every [PROTECTION_REFRESH_MS] while
 * shown: adb-setup may be granting the permissions meanwhile. When they arrive, the guard is started; when
 * they are taken back, a guard still running is stopped (what is shown is what it does).
 */
@Composable
fun rememberProtectionStatus(): State<ProtectionStatus?> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<ProtectionStatus?>(null, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val status = withContext(Dispatchers.Default) { ProtectionChecks.read(context, GuardService.running) }
                if (status.guardCanRun && !status.guardRunning) GuardService.start(context)
                if (!status.guardCanRun && status.guardRunning) GuardService.stop(context)
                value = status
                delay(PROTECTION_REFRESH_MS)
            }
        }
    }
}

private enum class Level { OK, ADVICE, MISSING }

/**
 * «حالة الحماية» (SPEC §4, §7): each thing the guard needs, granted or not and the adb command that grants
 * it; the advice (the official YouTube app, the automatic time); how fast the guard really brought the app
 * back (SPEC acceptance: ≤ 2 s after Home, measured on the TV); and, plainly, what it cannot prevent.
 */
@Composable
fun ProtectionScreen(
    status: ProtectionStatus?,
    returns: List<ReturnTime>,
    packageName: String,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ShashaDimens.ScreenPadding)
            .testTag("protection"),
    ) {
        ScreenHeader(stringResource(R.string.protection_title), stringResource(R.string.protection_subtitle)) {
            SummaryPill(status)
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel(stringResource(R.string.protection_section_guard), icon = ShashaIcons.Shield)
        Spacer(Modifier.height(8.dp))
        val s = status
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusRow(
                level = s.level { it.overlay },
                title = stringResource(R.string.protection_overlay_title),
                body = stringResource(R.string.protection_overlay_body),
                value = s.granted { it.overlay },
                command = ProtectionCommands.overlay(packageName).takeIf { s != null && !s.overlay },
                modifier = Modifier.focusRequester(first).testTag("protection_overlay"),
            )
            StatusRow(
                level = s.level { it.usageAccess },
                title = stringResource(R.string.protection_usage_title),
                body = stringResource(R.string.protection_usage_body),
                value = s.granted { it.usageAccess },
                command = ProtectionCommands.usageAccess(packageName).takeIf { s != null && !s.usageAccess },
                modifier = Modifier.testTag("protection_usage"),
            )
            StatusRow(
                level = s.level { it.guardActive },
                title = stringResource(R.string.protection_guard_title),
                body = stringResource(
                    when {
                        s == null || s.guardActive -> R.string.protection_guard_body_running
                        !s.guardCanRun -> R.string.protection_guard_body_waiting
                        else -> R.string.protection_guard_body_stopped
                    },
                ),
                value = s?.let { stringResource(if (it.guardActive) R.string.protection_state_running else R.string.protection_state_stopped) },
                command = ProtectionCommands.startApp(packageName).takeIf { s != null && s.guardCanRun && !s.guardRunning },
                modifier = Modifier.testTag("protection_guard"),
            )
        }
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.protection_section_other))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusRow(
                level = s.level { it.installPackages },
                title = stringResource(R.string.protection_install_title),
                body = stringResource(R.string.protection_install_body),
                value = s.granted { it.installPackages },
                command = ProtectionCommands.installPackages(packageName).takeIf { s != null && !s.installPackages },
                modifier = Modifier.testTag("protection_install"),
            )
            val youtube = s?.youtube
            StatusRow(
                level = if (youtube == YouTubeAppState.ENABLED) Level.ADVICE else Level.OK,
                title = stringResource(R.string.protection_youtube_title),
                body = stringResource(
                    when (youtube) {
                        YouTubeAppState.ENABLED -> R.string.protection_youtube_body_enabled
                        YouTubeAppState.NOT_INSTALLED -> R.string.protection_youtube_body_missing
                        else -> R.string.protection_youtube_body_disabled
                    },
                ),
                value = youtube?.let {
                    stringResource(
                        when (it) {
                            YouTubeAppState.ENABLED -> R.string.protection_state_enabled
                            YouTubeAppState.DISABLED -> R.string.protection_state_disabled
                            YouTubeAppState.NOT_INSTALLED -> R.string.protection_state_not_installed
                        },
                    )
                },
                command = ProtectionCommands.disableYouTube.takeIf { youtube == YouTubeAppState.ENABLED },
                modifier = Modifier.testTag("protection_youtube"),
            )
            val autoTime = s?.autoTime ?: true
            StatusRow(
                level = if (autoTime) Level.OK else Level.ADVICE,
                title = stringResource(R.string.protection_time_title),
                body = stringResource(if (autoTime) R.string.protection_time_body_on else R.string.protection_time_body_off),
                value = s?.let { stringResource(if (autoTime) R.string.protection_state_on else R.string.protection_state_off) },
                command = ProtectionCommands.autoTime.takeIf { !autoTime },
                modifier = Modifier.testTag("protection_time"),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.protection_commands_note),
            style = MaterialTheme.typography.bodySmall,
            color = ShashaColors.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.protection_section_returns), icon = ShashaIcons.Timer)
        Spacer(Modifier.height(8.dp))
        ReturnsCard(returns, zone)
        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.protection_section_limits), icon = ShashaIcons.Info)
        Spacer(Modifier.height(8.dp))
        LimitsCard(packageName)
    }
}

private fun ProtectionStatus?.level(ok: (ProtectionStatus) -> Boolean): Level = when {
    this == null || ok(this) -> Level.OK
    else -> Level.MISSING
}

@Composable
private fun ProtectionStatus?.granted(ok: (ProtectionStatus) -> Boolean): String? =
    this?.let { stringResource(if (ok(it)) R.string.protection_state_granted else R.string.protection_state_missing) }

@Composable
private fun Level.tone(): Color = when (this) {
    Level.OK -> LocalAccent.current.bright
    Level.ADVICE -> ShashaColors.Secondary
    Level.MISSING -> ShashaColors.Error
}

private fun Level.icon(): ImageVector = when (this) {
    Level.OK -> ShashaIcons.Check
    Level.ADVICE -> ShashaIcons.Info
    Level.MISSING -> ShashaIcons.Close
}

/** «الحماية تعمل», or how many required things are missing. */
@Composable
private fun SummaryPill(status: ProtectionStatus?, modifier: Modifier = Modifier) {
    val formats = rememberFormats()
    val (text, tone) = when {
        status == null -> stringResource(R.string.protection_checking) to ShashaColors.Muted
        status.missing == 0 -> stringResource(R.string.protection_all_good) to LocalAccent.current.bright
        else -> formats.count(R.array.protection_missing_count, status.missing.toLong()) to ShashaColors.Error
    }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .background(tone.copy(alpha = 0.12f), shape)
            .border(1.dp, tone.copy(alpha = 0.45f), shape)
            .padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
            .testTag("protection_summary"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.Shield, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = tone)
    }
}

/**
 * One item: its state in colour, what it is for, and (when it is not as it should be) the command that sets
 * it. Focusable, so the remote scrolls the page item by item.
 */
@Composable
private fun StatusRow(level: Level, title: String, body: String, value: String?, command: String?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val tone = level.tone()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent.main else ShashaColors.Outline, shape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(tone.copy(alpha = 0.14f), CircleShape)
                .border(1.dp, tone.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(level.icon(), contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            if (command != null) {
                Spacer(Modifier.height(8.dp))
                CommandText(command)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                modifier = Modifier
                    .background(tone.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** An adb command as it is typed: left to right, fixed width. */
@Composable
private fun CommandText(command: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, textDirection = TextDirection.Ltr),
            color = ShashaColors.Text,
            modifier = Modifier
                .background(ShashaColors.Background, RoundedCornerShape(10.dp))
                .border(1.dp, ShashaColors.Outline, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("protection_command"),
        )
    }
}

/** The last returns the guard measured, newest first; the goal beside them. */
@Composable
private fun ReturnsCard(returns: List<ReturnTime>, zone: ZoneId) {
    val times = rememberTimeTexts()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) LocalAccent.current.main else ShashaColors.Outline, shape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("protection_returns"),
    ) {
        if (returns.isEmpty()) {
            Text(stringResource(R.string.protection_returns_empty), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
        } else {
            returns.take(SHOWN_RETURNS).forEach { back -> ReturnLine(back, times, zone) }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.protection_returns_goal), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
    }
}

@Composable
private fun ReturnLine(back: ReturnTime, times: TimeTexts, zone: ZoneId) {
    val fast = back.backMs <= GOAL_MS
    val tone = if (fast) LocalAccent.current.bright else ShashaColors.Secondary
    val at = Instant.ofEpochMilli(back.atEpochMs).atZone(zone)
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.protection_return_seconds, String.format(Locale.ROOT, "%.1f", back.backMs / 1000.0)),
            style = MaterialTheme.typography.titleMedium,
            color = tone,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(
                R.string.protection_return_from,
                back.fromPackage.ifEmpty { stringResource(R.string.protection_return_unknown_app) },
                times.time(at.hour * 60 + at.minute),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = ShashaColors.Muted,
            maxLines = 1,
        )
    }
}

/**
 * SPEC: «القفل ليس منيعاً» — what the guard cannot prevent, said plainly; and the one way it can shut the
 * owner out (the parent PIN locked out while a restriction lasts), with its way out.
 */
@Composable
private fun LimitsCard(packageName: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) ShashaColors.SurfaceHigh else ShashaColors.Surface, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) LocalAccent.current.main else ShashaColors.Outline, shape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("protection_limits"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val lines = listOf(
            stringResource(R.string.protection_limits_outside),
            stringResource(R.string.protection_limits_force_stop),
            stringResource(R.string.protection_limits_keys),
            stringResource(R.string.protection_limits_owner),
            stringResource(R.string.protection_limits_pin_lockout, ProtectionCommands.forceStop(packageName)),
        )
        for (line in lines) {
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Secondary)
                Spacer(Modifier.width(10.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Text, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The settings' way in: a pill in the header with the state at a glance («الحماية تعمل», or what is
 * missing). [status] null while it is read.
 */
@Composable
fun ProtectionChip(status: ProtectionStatus?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val formats = rememberFormats()
    val ok = status != null && status.missing == 0
    val tone = when {
        status == null -> ShashaColors.Muted
        ok -> LocalAccent.current.bright
        else -> ShashaColors.Error
    }
    val state = when {
        status == null -> stringResource(R.string.protection_checking)
        ok -> stringResource(R.string.protection_all_good)
        else -> formats.count(R.array.protection_missing_count, status.missing.toLong())
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.testTag("settings_protection"),
        shape = RoundedCornerShape(percent = 50),
        containerColor = ShashaColors.Surface,
        scale = 1.06f,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShashaIcons.Shield, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_protection), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text("·", style = MaterialTheme.typography.titleSmall, color = ShashaColors.Muted)
            Spacer(Modifier.width(8.dp))
            Text(state, style = MaterialTheme.typography.titleSmall, color = tone)
        }
    }
}

/** How often «حالة الحماية» reads the system again while it is shown. */
const val PROTECTION_REFRESH_MS = 2_000L

private const val SHOWN_RETURNS = 5

/** SPEC acceptance: Home during a child's lock → back within 2 s. */
private const val GOAL_MS = 2_000L

package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import java.util.Locale

/**
 * «حالة الحماية» at a glance (SPEC §4, §7): each thing the guard needs and the advice, granted or not, the last
 * measured return; the full screen (the adb commands, the limits) is one OK away.
 */
@Composable
internal fun ProtectionPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val status = state.protection
    PaneColumn {
        PaneRow(
            title = stringResource(R.string.hub_protection_open),
            onClick = actions.openProtection,
            modifier = Modifier.focusRequester(first).paneItem("hub_protection_open"),
            icon = ShashaIcons.Shield,
            body = stringResource(R.string.hub_protection_open_body),
        )
        if (status == null) {
            Loading()
        } else {
            // Focusable too (OK opens the same details): moving onto it scrolls the lines below it into view.
            StatusCard(onClick = actions.openProtection, modifier = Modifier.paneItem("hub_protection_status")) {
                val granted = stringResource(R.string.protection_state_granted)
                val missing = stringResource(R.string.protection_state_missing)
                StatusLine(stringResource(R.string.protection_overlay_title), if (status.overlay) granted else missing, Level.of(status.overlay))
                StatusLine(stringResource(R.string.protection_usage_title), if (status.usageAccess) granted else missing, Level.of(status.usageAccess))
                StatusLine(
                    stringResource(R.string.protection_guard_title),
                    stringResource(if (status.guardActive) R.string.protection_state_running else R.string.protection_state_stopped),
                    Level.of(status.guardActive),
                )
                StatusLine(stringResource(R.string.protection_install_title), if (status.installPackages) granted else missing, Level.of(status.installPackages))
                Box(Modifier.fillMaxWidth().height(1.dp).background(ShashaColors.Outline))
                StatusLine(stringResource(R.string.protection_youtube_title), youtubeText(status), if (status.youtube == YouTubeAppState.ENABLED) Level.ADVICE else Level.OK)
                StatusLine(
                    stringResource(R.string.protection_time_title),
                    stringResource(if (status.autoTime) R.string.protection_state_on else R.string.protection_state_off),
                    if (status.autoTime) Level.OK else Level.ADVICE,
                )
            }
        }
        val back = state.lastReturn
        if (back != null) {
            val seconds = String.format(Locale.ROOT, "%.1f", back.backMs / 1000.0)
            InfoLine(ShashaIcons.Timer, stringResource(R.string.hub_protection_last_return, seconds), tint = LocalAccent.current.bright, textColor = ShashaColors.Text)
        }
        InfoLine(ShashaIcons.Info, stringResource(R.string.protection_returns_goal))
    }
}

@Composable
private fun youtubeText(status: ProtectionStatus): String = stringResource(
    when (status.youtube) {
        YouTubeAppState.DISABLED -> R.string.protection_state_disabled
        YouTubeAppState.ENABLED -> R.string.protection_state_enabled
        YouTubeAppState.NOT_INSTALLED -> R.string.protection_state_not_installed
    },
)

/** The protection's one-line verdict for the pane's title and the list: all good, or how many things are missing. */
@Composable
internal fun ProtectionTag(status: ProtectionStatus?) {
    val formats = rememberFormats()
    when {
        status == null -> Tag(stringResource(R.string.protection_checking), ShashaColors.Muted)
        status.missing == 0 -> Tag(stringResource(R.string.protection_all_good), LocalAccent.current.bright, icon = ShashaIcons.Check)
        else -> Tag(formats.count(R.array.protection_missing_count, status.missing.toLong()), ShashaColors.Error, icon = ShashaIcons.Close)
    }
}

private enum class Level {
    OK, ADVICE, MISSING;

    companion object {
        fun of(ok: Boolean) = if (ok) OK else MISSING
    }
}

/** A card of status lines that takes the focus and opens [onClick]. */
@Composable
private fun StatusCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceVariant,
        scale = 1.01f,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusLine(title: String, value: String, level: Level) {
    val tone = when (level) {
        Level.OK -> LocalAccent.current.bright
        Level.ADVICE -> ShashaColors.Secondary
        Level.MISSING -> ShashaColors.Error
    }
    val icon = when (level) {
        Level.OK -> ShashaIcons.Check
        Level.ADVICE -> ShashaIcons.Info
        Level.MISSING -> ShashaIcons.Close
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
        Tag(value, tone)
    }
}

/**
 * «الخادم المحلي» (SPEC §5, §7): on/off and the pairing screen (the QR code, a new token), both behind the
 * parent PIN like before: pairing a phone hands it the TV.
 */
@Composable
internal fun RemotePane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val remote = state.remote
    PaneColumn {
        SwitchRow(
            title = stringResource(R.string.hub_remote_switch),
            body = stringResource(R.string.hub_remote_switch_body),
            checked = remote?.enabled ?: false,
            onToggle = { remote?.let { actions.setRemoteEnabled(!it.enabled) } },
            modifier = Modifier.focusRequester(first).paneItem("hub_remote_switch"),
            icon = RemoteIcons.Wifi,
            locked = !state.unlocked,
        )
        PaneRow(
            title = stringResource(R.string.hub_remote_pairing),
            onClick = actions.openRemote,
            modifier = Modifier.paneItem("hub_remote_pairing"),
            icon = RemoteIcons.QrCode,
            body = stringResource(R.string.hub_remote_pairing_body),
            locked = !state.unlocked,
        )
        val address = remote?.address
        if (address != null) {
            InfoLine(RemoteIcons.Wifi, stringResource(R.string.hub_remote_address, address), tint = LocalAccent.current.bright, textColor = ShashaColors.Text)
        }
        InfoLine(ShashaIcons.Lock, stringResource(R.string.hub_remote_note))
    }
}

/** The server's state for the pane's title and the list. */
@Composable
internal fun RemoteTag(remote: RemoteSummary?) {
    val status = remote?.status ?: return
    val (text, tone) = when (status) {
        PairingStatus.RUNNING -> R.string.remote_status_running to LocalAccent.current.bright
        PairingStatus.STARTING -> R.string.remote_status_starting to ShashaColors.Muted
        PairingStatus.NO_NETWORK -> R.string.remote_status_no_network to ShashaColors.Secondary
        PairingStatus.FAILED -> R.string.remote_status_failed to ShashaColors.Error
        PairingStatus.OFF -> R.string.remote_status_off to ShashaColors.Muted
    }
    Tag(stringResource(text), tone)
}

/** «الاستيراد والتصدير»: the JSON backup (behind the parent PIN: it holds everything) and the Takeout import. */
@Composable
internal fun DataPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val formats = rememberFormats()
    PaneColumn {
        PaneRow(
            title = stringResource(R.string.hub_data_backup),
            onClick = actions.openBackup,
            modifier = Modifier.focusRequester(first).paneItem("hub_data_backup"),
            icon = RemoteIcons.Backup,
            body = stringResource(R.string.backup_subtitle),
            locked = !state.unlocked,
        )
        PaneRow(
            title = stringResource(R.string.hub_data_takeout),
            onClick = actions.openImport,
            modifier = Modifier.paneItem("hub_data_takeout"),
            icon = ShashaIcons.Download,
            body = stringResource(R.string.hub_data_takeout_body),
            value = state.subscriptionCount?.takeIf { it > 0 }?.let { formats.count(R.array.count_channels, it.toLong()) },
        )
        InfoLine(RemoteIcons.Phone, stringResource(R.string.backup_phone_note))
    }
}

/**
 * «فحص التحديثات» (SPEC §6): the installed version and the check itself. Phase 7 wires the GitHub Releases
 * updater behind [SettingsHubActions.openUpdates]; until then it opens the placeholder that says so.
 */
@Composable
internal fun UpdatesPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    PaneColumn(spacing = 14.dp) {
        VersionBlock(state.versions)
        Row {
            ShashaButton(
                stringResource(R.string.action_check_updates),
                actions.openUpdates,
                icon = ShashaIcons.SystemUpdate,
                primary = true,
                modifier = Modifier.focusRequester(first).paneItem("hub_check_updates"),
            )
        }
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_updates_how))
        InfoLine(ShashaIcons.Build, stringResource(R.string.hub_updates_when))
        InfoLine(ShashaIcons.Computer, stringResource(R.string.hub_updates_adb))
    }
}

/** The logo, the version and its build number, and the extractor's version. */
@Composable
internal fun VersionBlock(versions: AppVersions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.hub_version, versions.name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.hub_build_line, versions.code.toString(), versions.extractor),
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
            )
        }
    }
}

/** «تشخيص»: the test videos, and the facts to give when something breaks. */
@Composable
internal fun DiagnosticsPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val formats = rememberFormats()
    PaneColumn {
        PaneRow(
            title = stringResource(R.string.diag_title),
            onClick = actions.openDiagnostics,
            modifier = Modifier.focusRequester(first).paneItem("hub_diagnostics_videos"),
            icon = ShashaIcons.PlaylistPlay,
            body = stringResource(R.string.hub_diag_videos_body),
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.hub_diag_facts), icon = ShashaIcons.Build)
        // Taking the focus, it lets the remote scroll down to the hint under it.
        ReadingCard(Modifier.paneItem("hub_diagnostics_facts"), spacing = 10.dp) {
            val versions = state.versions
            InfoLine(ShashaIcons.SystemUpdate, stringResource(R.string.hub_fact_app, versions.name, versions.code.toString()), textColor = ShashaColors.Text)
            InfoLine(ShashaIcons.Build, stringResource(R.string.hub_fact_extractor, versions.extractor), textColor = ShashaColors.Text)
            state.device?.let { device ->
                val name = listOf(device.maker, device.model).filter { it.isNotBlank() }.joinToString(" ")
                InfoLine(ShashaIcons.Computer, stringResource(R.string.hub_fact_device, name, device.android, device.sdk.toString()), textColor = ShashaColors.Text)
            }
            state.syncHealth?.let { health ->
                val text = when {
                    health.channels == 0 -> stringResource(R.string.hub_fact_no_subscriptions)
                    health.lastAttemptAtMs == null -> stringResource(
                        R.string.hub_fact_feed_never,
                        formats.count(R.array.count_channels, health.channels.toLong()),
                    )
                    else -> stringResource(
                        R.string.hub_fact_feed,
                        formats.count(R.array.count_channels, health.channels.toLong()),
                        formats.ago(health.lastAttemptAtMs, state.nowMs),
                    )
                }
                val failing = health.failed > 0
                InfoLine(ShashaIcons.Subscriptions, text, tint = if (failing) ShashaColors.Secondary else ShashaColors.Muted, textColor = ShashaColors.Text)
                if (failing) {
                    InfoLine(ShashaIcons.Info, stringResource(R.string.hub_fact_feed_failed, health.failed.toString()), tint = ShashaColors.Secondary)
                    health.lastError?.let { error ->
                        // The error as the extractor named it, left to right, under the Arabic line.
                        Text(
                            "${error.kind.name}: ${error.technicalName}",
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                            color = ShashaColors.Muted,
                            textAlign = TextAlign.Right,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                        )
                    }
                }
            }
        }
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_diag_hint))
    }
}

/** A status colour for the list's small dot: teal all good, red something missing, grey not known yet. */
@Composable
internal fun protectionDot(status: ProtectionStatus?): Color = when {
    status == null -> ShashaColors.Muted
    status.missing == 0 -> LocalAccent.current.bright
    else -> ShashaColors.Error
}

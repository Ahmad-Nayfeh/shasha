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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.ReturnTime
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import java.util.Locale

/**
 * «حالة الحماية» at a glance (SPEC §4, §7): the last measured return and the goal it is measured against, then
 * each thing the guard needs and the advice, granted or not; the full screen (the adb commands, the limits) is one
 * OK away.
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
        // The number to compare on the TV, in view from the start. Not under the card: nothing below the pane's last
        // element could take the focus and scroll it into view.
        ReturnLines(state.lastReturn)
        if (status == null) {
            Loading()
        } else {
            // Focusable too (OK opens the same details): moving onto it brings all of it into view.
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
    }
}

/** The guard's last measured return to «شاشة» after another app took the screen, and the goal (SPEC §4: ≤ 2 s). */
@Composable
private fun ReturnLines(back: ReturnTime?) {
    if (back != null) {
        val seconds = String.format(Locale.ROOT, "%.1f", back.backMs / 1000.0)
        InfoLine(ShashaIcons.Timer, stringResource(R.string.hub_protection_last_return, seconds), tint = LocalAccent.current.bright, textColor = ShashaColors.Text)
    }
    InfoLine(ShashaIcons.Info, stringResource(R.string.protection_returns_goal), modifier = Modifier.testTag("hub_protection_goal"))
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
 * «التحكم من الجوال» (SPEC §5, §7): on/off and the pairing screen (the QR code, a new token), both behind the
 * parent PIN like before: pairing a phone hands it the TV. In plain words only: the address is on the pairing screen
 * under its QR code, and the port shows only when it matters, when the server could not start (another app holds
 * the port) or the port was changed before ([PortRow]).
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
        val port = state.remotePort
        if (remote?.status == PairingStatus.FAILED || (port != null && port != RemoteSettings.DEFAULT_PORT)) PortRow(state, actions)
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
 * «فحص التحديثات» (SPEC §6): the installed version and one button, nothing technical (owner decision: the licences
 * are one closed row at the bottom of «حول», [AboutPane]). The button opens the updater ([SettingsHubActions.openUpdates],
 * ui/screens/updates), which checks at once and, when a newer version is out, downloads and installs it by itself.
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
        // Under the button it starts from, in view as the pane opens: lower down it would sit between cards that take
        // the focus, and the pivot scrolling would carry it from under the bottom fade to under the top one.
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_updates_how))
        InfoLine(ShashaIcons.Build, stringResource(R.string.hub_updates_when))
    }
}

/** The logo and the version, as people say it (no build number, no library versions). */
@Composable
internal fun VersionBlock(versions: AppVersions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("hub_version"), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.hub_version, versions.name), style = MaterialTheme.typography.headlineSmall)
    }
}

/** A status colour for the list's small dot: teal all good, red something missing, grey not known yet. */
@Composable
internal fun protectionDot(status: ProtectionStatus?): Color = when {
    status == null -> ShashaColors.Muted
    status.missing == 0 -> LocalAccent.current.bright
    else -> ShashaColors.Error
}

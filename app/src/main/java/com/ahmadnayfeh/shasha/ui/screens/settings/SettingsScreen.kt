package com.ahmadnayfeh.shasha.ui.screens.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinDialog
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.navigation.SettingsActions
import com.ahmadnayfeh.shasha.ui.parental.LocalParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

@Composable
fun SettingsRoute(focus: ContentFocus, settings: SettingsActions) {
    val container = LocalContext.current.appContainer
    // The profile's one parent unlock, shared with the card menus' parental actions.
    val vm = LocalParentUnlock.current ?: containerViewModel { ParentUnlock(it.parentPin) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guarded(open: () -> Unit): () -> Unit = { if (vm.isUnlocked) open() else pending = open }
    SettingsScreen(
        focus = focus,
        appVersion = BuildConfig.VERSION_NAME,
        extractorVersion = container.videoSource.extractorVersion,
        onProfiles = guarded(settings.openProfiles),
        onBlocklist = guarded(settings.openBlocklist),
        onStats = guarded(settings.openStats),
        onChangePin = settings.openChangePin,
        onImport = settings.openImport,
        onDiagnostics = settings.openDiagnostics,
    )
    pending?.let { open ->
        PinDialog(onDismiss = { pending = null }) {
            ParentPinPad(
                title = stringResource(R.string.pin_parent_title),
                subtitle = stringResource(R.string.settings_pin_body),
                check = vm::check,
                onAccepted = {
                    pending = null
                    open()
                },
                initialLockMillis = vm::lockMillis,
            )
        }
    }
}

/**
 * الإعدادات: the parental sections first (they ask for the parent PIN: the adult profile may be left on
 * while a child walks by), then the general ones. «تغيير رمز PIN» asks for the current PIN itself.
 */
@Composable
fun SettingsScreen(
    focus: ContentFocus,
    appVersion: String,
    extractorVersion: String,
    onProfiles: () -> Unit,
    onBlocklist: () -> Unit,
    onStats: () -> Unit,
    onChangePin: () -> Unit,
    onImport: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    TakeFocusWhenReady(focus, ready = true)
    FocusArea(focus, first, modifier.fillMaxSize().padding(GridDefaults.ContentPadding).testTag("settings")) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) {
                Text(
                    stringResource(R.string.settings_version, appVersion, extractorVersion),
                    style = MaterialTheme.typography.labelMedium,
                    color = ShashaColors.Muted,
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.settings_section_parental), icon = ShashaIcons.Lock)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingsTile(ShashaIcons.Group, stringResource(R.string.settings_profiles), stringResource(R.string.settings_profiles_body), onProfiles, Modifier.weight(1f).focusRequester(first).testTag("settings_profiles"), locked = true)
                SettingsTile(ShashaIcons.Block, stringResource(R.string.settings_blocklist), stringResource(R.string.settings_blocklist_body), onBlocklist, Modifier.weight(1f).testTag("settings_blocklist"), locked = true)
                SettingsTile(ShashaIcons.BarChart, stringResource(R.string.settings_stats), stringResource(R.string.settings_stats_body), onStats, Modifier.weight(1f).testTag("settings_stats"), locked = true)
            }
            Spacer(Modifier.height(18.dp))
            SectionLabel(stringResource(R.string.settings_section_general))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                SettingsTile(ShashaIcons.Key, stringResource(R.string.settings_change_pin), stringResource(R.string.settings_change_pin_body), onChangePin, Modifier.weight(1f).testTag("settings_change_pin"))
                SettingsTile(ShashaIcons.Download, stringResource(R.string.settings_import), stringResource(R.string.settings_import_body), onImport, Modifier.weight(1f).testTag("settings_import"))
                SettingsTile(ShashaIcons.Build, stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_body), onDiagnostics, Modifier.weight(1f).testTag("settings_diagnostics"))
            }
        }
    }
}

/**
 * One section: its icon, name and what it holds, side by side so both rows of tiles fit the screen.
 * [locked]: a small lock says the parent PIN is asked first.
 */
@Composable
private fun SettingsTile(icon: ImageVector, title: String, body: String, onClick: () -> Unit, modifier: Modifier = Modifier, locked: Boolean = false) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(TileHeight),
        shape = RoundedCornerShape(22.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.04f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(accent.tint(0.14f), CircleShape)
                    .border(1.dp, accent.tint(0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent.bright, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (locked) {
                        Spacer(Modifier.width(8.dp))
                        Icon(ShashaIcons.Lock, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                // Read from the sofa: the body size of the other screens, on up to three lines.
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(ShashaIcons.Chevron, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
        }
    }
}

private val TileHeight = 128.dp

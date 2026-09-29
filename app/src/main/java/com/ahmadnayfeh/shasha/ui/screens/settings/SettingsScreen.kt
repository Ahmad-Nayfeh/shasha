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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * الإعدادات, for now: «تشخيص» (the test videos) and «استيراد الاشتراكات». The rest of SPEC §7 arrives
 * with the phases that need it.
 */
@Composable
fun SettingsScreen(
    focus: ContentFocus,
    appVersion: String,
    extractorVersion: String,
    onDiagnostics: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    TakeFocusWhenReady(focus, ready = true)
    FocusArea(focus, first, modifier.fillMaxSize().padding(GridDefaults.ContentPadding).testTag("settings")) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SettingsTile(
                    icon = ShashaIcons.Download,
                    title = stringResource(R.string.settings_import),
                    body = stringResource(R.string.settings_import_body),
                    onClick = onImport,
                    modifier = Modifier.weight(1f).focusRequester(first).testTag("settings_import"),
                )
                SettingsTile(
                    icon = ShashaIcons.Build,
                    title = stringResource(R.string.settings_diagnostics),
                    body = stringResource(R.string.settings_diagnostics_body),
                    onClick = onDiagnostics,
                    modifier = Modifier.weight(1f).testTag("settings_diagnostics"),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.settings_version, appVersion, extractorVersion),
                style = MaterialTheme.typography.labelMedium,
                color = ShashaColors.Muted,
            )
        }
    }
}

@Composable
private fun SettingsTile(icon: ImageVector, title: String, body: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(196.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.04f,
    ) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(ShashaColors.Primary.copy(alpha = 0.14f), CircleShape)
                        .border(1.dp, ShashaColors.Primary.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(ShashaIcons.Chevron, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }
}

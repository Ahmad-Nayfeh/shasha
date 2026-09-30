package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * «حول»: the version and its build number, the extractor's version, the plain note that the app breaks
 * YouTube's terms and is for personal use only, SponsorBlock's attribution (CC BY-NC-SA 4.0), and every
 * library the APK carries with its licence. Each block takes the focus in turn, so the D-pad reads the page
 * from top to bottom.
 */
@Composable
internal fun AboutPane(state: SettingsHubState, first: FocusRequester) {
    PaneColumn(spacing = 12.dp) {
        ReadingCard(Modifier.focusRequester(first).paneItem("about_version")) {
            VersionBlock(state.versions)
        }
        ReadingCard(Modifier.paneItem("about_terms"), tone = ShashaColors.Secondary) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SettingsIcons.Warning, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.hub_about_terms_title), style = MaterialTheme.typography.titleMedium, color = ShashaColors.Secondary)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.hub_about_terms_body), style = MaterialTheme.typography.bodyMedium)
        }
        ReadingCard(Modifier.paneItem("about_sponsorblock")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ShashaIcons.SkipNext, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.hub_about_data_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            SponsorBlockAttribution()
        }
        Spacer(Modifier.height(2.dp))
        SectionLabel(stringResource(R.string.hub_about_licenses), icon = SettingsIcons.Description)
        Licenses.libraries.forEach { LicenseRow(it) }
        Spacer(Modifier.height(2.dp))
        SectionLabel(stringResource(R.string.hub_about_ideas), icon = SettingsIcons.Lightbulb)
        Licenses.ideas.forEach { LicenseRow(it) }
        Spacer(Modifier.height(2.dp))
        InfoLine(ShashaIcons.Shield, stringResource(R.string.hub_about_footer))
    }
}

/** A library: its name (as the project writes it), what it does here, and its licence. Focusable to be read. */
@Composable
private fun LicenseRow(entry: LicenseEntry) {
    FocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).paneItem("license_${entry.name}"),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceVariant,
        scale = 1.01f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // A Latin name keeps its own order and, only as wide as itself, hangs from the right like the Arabic.
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(entry.role), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Tag(entry.license, LocalAccent.current.bright)
        }
    }
}

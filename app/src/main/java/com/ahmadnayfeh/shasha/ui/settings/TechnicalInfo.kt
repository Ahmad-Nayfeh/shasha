package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
 * «معلومات تقنية والتراخيص», at the bottom of «فحص التحديثات» (it used to be on «حول», then under «تشخيص»): SponsorBlock's
 * attribution (CC BY-NC-SA 4.0, which its licence requires) and every library the APK carries with its licence
 * ([Licenses]). The version and the extractor's version are the pane's own first block ([VersionBlock]), not repeated
 * here. Compact: small rows with little between them. Every block takes the focus in turn, so the D-pad reads the
 * section from top to bottom.
 */
@Composable
internal fun TechnicalInfo() {
    SectionLabel(stringResource(R.string.hub_tech_title), icon = SettingsIcons.Description)
    ReadingCard(Modifier.paneItem("tech_sponsorblock"), spacing = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ShashaIcons.SkipNext, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.hub_tech_data_title), style = MaterialTheme.typography.titleSmall)
        }
        SponsorBlockAttribution()
    }
    SectionLabel(stringResource(R.string.hub_tech_licenses), icon = SettingsIcons.Description)
    Column(verticalArrangement = Arrangement.spacedBy(CompactGap)) {
        Licenses.libraries.forEach { LicenseRow(it) }
    }
    SectionLabel(stringResource(R.string.hub_tech_ideas), icon = SettingsIcons.Lightbulb)
    Column(verticalArrangement = Arrangement.spacedBy(CompactGap)) {
        Licenses.ideas.forEach { LicenseRow(it) }
    }
    Spacer(Modifier.height(2.dp))
    InfoLine(ShashaIcons.Shield, stringResource(R.string.hub_tech_footer))
}

/** A library: its name (as the project writes it), what it does here, and its licence. Focusable to be read. */
@Composable
private fun LicenseRow(entry: LicenseEntry) {
    FocusSurface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).paneItem("license_${entry.name}"),
        shape = RoundedCornerShape(14.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceVariant,
        scale = 1.01f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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

private val CompactGap = 6.dp

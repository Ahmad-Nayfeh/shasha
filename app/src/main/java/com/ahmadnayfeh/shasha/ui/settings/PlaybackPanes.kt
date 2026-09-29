package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * «فئات SponsorBlock» (SPEC §1, §7): each category with its Arabic name, a line about it and its switch (OK flips
 * it, stored at once for the whole household). With none on, SponsorBlock is not asked at all. The data's
 * attribution closes the pane.
 */
@Composable
internal fun SponsorBlockPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val selected = state.sponsorCategories
    if (selected == null) {
        PaneColumn { Loading() }
        return
    }
    PaneColumn {
        if (selected.isEmpty()) NothingSkipped()
        SponsorCategory.entries.forEachIndexed { index, category ->
            val on = category in selected
            SwitchRow(
                title = stringResource(category.label),
                body = stringResource(category.description),
                checked = on,
                onToggle = { actions.setSponsorCategory(category, !on) },
                modifier = Modifier.firstIf(index == 0, first).paneItem("hub_sponsor_${category.apiKey}"),
            )
        }
        if (selected != SponsorCategory.DEFAULTS) {
            PaneRow(
                title = stringResource(R.string.hub_sponsor_defaults),
                onClick = actions.resetSponsorCategories,
                modifier = Modifier.paneItem("hub_sponsor_reset"),
                icon = ShashaIcons.Refresh,
                body = SponsorCategory.DEFAULTS.map { stringResource(it.label) }.joinToString("، "),
                trailing = {},
            )
        }
        Spacer(Modifier.height(4.dp))
        SponsorBlockAttribution()
    }
}

/** The data's licence: SponsorBlock's own English sentence, exactly, under an Arabic line. */
@Composable
internal fun SponsorBlockAttribution(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("sponsorblock_attribution")) {
        InfoLine(ShashaIcons.Info, stringResource(R.string.sponsorblock_attribution))
        Text(
            stringResource(R.string.sponsorblock_attribution_exact),
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
            color = ShashaColors.Muted.copy(alpha = 0.85f),
            // Hangs from the right like the Arabic above it, read left to right.
            textAlign = TextAlign.Right,
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp),
        )
    }
}

@Composable
private fun NothingSkipped() {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Secondary.copy(alpha = 0.10f), shape)
            .border(1.dp, ShashaColors.Secondary.copy(alpha = 0.35f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("sponsorblock_none"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.Info, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.sponsor_settings_none), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * «الجودة الافتراضية»: how high "Auto" may go, from 480p to 2160p (1080p by default, SPEC §1). The player adapts
 * below it and falls back by itself; the quality menu of a video still offers a fixed quality up to it.
 */
@Composable
internal fun QualityPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    PaneColumn(spacing = 8.dp) {
        PlaybackSettings.QUALITY_CAPS.forEach { height ->
            ChoiceRow(
                title = stringResource(R.string.hub_quality_option, height.toString()),
                body = stringResource(qualityBody(height)),
                selected = state.maxQuality == height,
                onClick = { actions.setMaxQuality(height) },
                // The focus comes in on the current choice.
                modifier = Modifier.firstIf(state.maxQuality == height, first).paneItem("hub_quality_$height"),
                badge = if (height == PlaybackSettings.DEFAULT_MAX_QUALITY) stringResource(R.string.sponsor_settings_default) else null,
            )
        }
        Spacer(Modifier.height(4.dp))
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_quality_note))
    }
}

private fun qualityBody(height: Int): Int = when (height) {
    2160 -> R.string.hub_quality_2160
    1440 -> R.string.hub_quality_1440
    1080 -> R.string.hub_quality_1080
    720 -> R.string.hub_quality_720
    else -> R.string.hub_quality_480
}

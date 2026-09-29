package com.ahmadnayfeh.shasha.sponsorblock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.screens.settings.Switch
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.launch

/**
 * Settings → «فئات SponsorBlock» (SPEC §7), ready for the settings' navigation: `Screen.SponsorBlock` →
 * `SponsorBlockRoute()`. The choice is the household's (every profile skips the same) and is written at once.
 */
@Composable
fun SponsorBlockRoute() {
    val container = LocalContext.current.appContainer
    val settings = container.sponsorBlockSettings
    val selected by settings.categories.collectAsStateWithLifecycle(initialValue = null)
    val chosen = selected ?: return
    SponsorBlockScreen(
        selected = chosen,
        onToggle = { category, on -> container.appScope.launch { settings.setEnabled(category, on) } },
        onReset = { container.appScope.launch { settings.reset() } },
    )
}

/**
 * Every category SponsorBlock can skip, each with a line about it and its switch (OK flips it); «الافتراضي»
 * brings SPEC §1's five back. With nothing chosen a quiet line says SponsorBlock is not asked at all. The data's
 * attribution closes the page (CC BY-NC-SA 4.0).
 */
@Composable
fun SponsorBlockScreen(
    selected: Set<SponsorCategory>,
    onToggle: (SponsorCategory, Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(modifier.fillMaxSize().padding(start = ShashaDimens.ScreenHorizontal, end = ShashaDimens.ScreenHorizontal, top = ShashaDimens.ScreenVertical).testTag("sponsorblock_settings")) {
        ScreenHeader(stringResource(R.string.sponsor_settings_title), stringResource(R.string.sponsor_settings_subtitle)) {
            if (selected != SponsorCategory.DEFAULTS) {
                ShashaButton(stringResource(R.string.sponsor_settings_default), onReset, icon = ShashaIcons.Refresh, compact = true, modifier = Modifier.testTag("sponsorblock_reset"))
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = ShashaDimens.ScreenVertical),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (selected.isEmpty()) {
                item(key = "none") { NothingChosen() }
            }
            itemsIndexed(SponsorCategory.entries, key = { _, it -> it.apiKey }) { index, category ->
                val on = category in selected
                CategoryRow(
                    category = category,
                    on = on,
                    onClick = { onToggle(category, !on) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            item(key = "attribution") {
                Column(Modifier.padding(top = 8.dp)) {
                    SectionLabel("SponsorBlock", icon = ShashaIcons.Info)
                    Text(
                        stringResource(R.string.sponsorblock_attribution),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShashaColors.Muted,
                        modifier = Modifier.fillMaxWidth().testTag("sponsorblock_attribution"),
                    )
                    // The licence's own wording, in its own reading order, hanging from the right like the rest.
                    Text(
                        stringResource(R.string.sponsorblock_attribution_original),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        color = ShashaColors.Muted.copy(alpha = 0.8f),
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: SponsorCategory, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .semantics { stateDescription = if (on) "on" else "off" }
            .testTag("sponsor_category_${category.apiKey}"),
        shape = RoundedCornerShape(18.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (on) accent.tint(0.16f) else ShashaColors.SurfaceVariant, CircleShape)
                    .border(1.dp, if (on) accent.tint(0.4f) else ShashaColors.Outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ShashaIcons.SkipNext, contentDescription = null, tint = if (on) accent.bright else ShashaColors.Muted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(category.label), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    stringResource(category.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShashaColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(if (on) R.string.sponsor_settings_on else R.string.sponsor_settings_off),
                style = MaterialTheme.typography.bodyLarge,
                color = if (on) accent.bright else ShashaColors.Muted,
            )
            Spacer(Modifier.width(12.dp))
            Switch(on)
        }
    }
}

@Composable
private fun NothingChosen() {
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
        Text(stringResource(R.string.sponsor_settings_none), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Text)
    }
}

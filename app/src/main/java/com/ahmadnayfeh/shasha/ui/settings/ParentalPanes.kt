package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.screens.settings.OptionsDialog
import com.ahmadnayfeh.shasha.ui.screens.settings.QuotaOptions
import com.ahmadnayfeh.shasha.ui.screens.settings.ScheduleDialog
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel
import com.ahmadnayfeh.shasha.ui.screens.settings.allowlistText
import com.ahmadnayfeh.shasha.ui.screens.settings.quotaText
import com.ahmadnayfeh.shasha.ui.screens.settings.scheduleText
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlin.math.absoluteValue

/** «الملفات ورمز الوالدين»: every profile (OK opens its page), managing them, and changing the PIN. */
@Composable
internal fun ProfilesPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val texts = rememberTimeTexts()
    PaneColumn {
        if (!state.unlocked) {
            LockedCard(actions.unlock, first)
        } else {
            val profiles = state.profiles
            if (profiles == null) {
                Loading()
            } else {
                SectionLabel(stringResource(R.string.profiles_title), icon = ShashaIcons.Group)
                profiles.forEachIndexed { index, profile ->
                    val kind = stringResource(if (profile.isChild) R.string.profile_kind_child else R.string.profile_kind_adult)
                    PaneRow(
                        title = profile.name,
                        onClick = { actions.openProfile(profile.id) },
                        modifier = Modifier.firstIf(index == 0, first).paneItem("hub_profile_${profile.id}"),
                        body = listOf(kind, quotaText(profile.dailyQuotaMinutes, texts), scheduleText(profile, texts)).joinToString("  ·  "),
                        leading = { ProfileAvatar(profile.avatar, profile.name, 40.dp) },
                    )
                }
                PaneRow(
                    title = stringResource(R.string.hub_profiles_manage),
                    onClick = actions.openProfiles,
                    modifier = Modifier.firstIf(profiles.isEmpty(), first).paneItem("hub_profiles_manage"),
                    icon = ShashaIcons.PersonAdd,
                    body = stringResource(R.string.hub_profiles_manage_body),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.pin_parent_title), icon = ShashaIcons.Key)
        PaneRow(
            title = stringResource(R.string.settings_change_pin),
            onClick = actions.openChangePin,
            modifier = Modifier.paneItem("hub_change_pin"),
            icon = ShashaIcons.Key,
            body = stringResource(R.string.settings_change_pin_body),
        )
        InfoLine(ShashaIcons.Info, stringResource(R.string.hub_pin_note))
    }
}

/** «قوائم السماح والحظر»: the household's blocklist and each child's allowlist. */
@Composable
internal fun ListsPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val formats = rememberFormats()
    PaneColumn {
        val profiles = state.profiles
        when {
            !state.unlocked -> LockedCard(actions.unlock, first)
            profiles == null -> Loading()
            else -> {
                SectionLabel(stringResource(R.string.hub_blocklist_section), icon = ShashaIcons.Block)
                val blocked = ParentalLists.householdBlocklist(profiles)
                val counts = listOfNotNull(
                    blocked.channels.size.takeIf { it > 0 }?.let { formats.count(R.array.count_channels, it.toLong()) },
                    blocked.titleKeywords.size.takeIf { it > 0 }?.let { formats.count(R.array.count_keywords, it.toLong()) },
                )
                PaneRow(
                    title = stringResource(R.string.blocklist_title),
                    onClick = actions.openBlocklist,
                    modifier = Modifier.focusRequester(first).paneItem("hub_blocklist"),
                    icon = ShashaIcons.Block,
                    body = stringResource(R.string.blocklist_subtitle),
                    value = counts.joinToString("  ·  ").ifEmpty { stringResource(R.string.hub_list_empty) },
                )
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.hub_allowlists_section), icon = ShashaIcons.Shield)
                val children = profiles.filter { it.isChild }
                if (children.isEmpty()) {
                    InfoLine(ShashaIcons.Info, stringResource(R.string.hub_no_children))
                }
                children.forEach { child ->
                    PaneRow(
                        title = stringResource(R.string.allowlist_title, child.name),
                        onClick = { actions.openAllowlist(child.id) },
                        modifier = Modifier.paneItem("hub_allowlist_${child.id}"),
                        body = allowlistText(child),
                        leading = { ProfileAvatar(child.avatar, child.name, 40.dp) },
                    )
                }
                InfoLine(ShashaIcons.Info, stringResource(R.string.hub_lists_hint))
            }
        }
    }
}

/**
 * «الحصص والجداول»: each profile's daily quota and allowed hours, changed right here (the same pickers as the
 * profile's own page; each change is stored at once).
 */
@Composable
internal fun TimePane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val texts = rememberTimeTexts()
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    PaneColumn(spacing = 12.dp) {
        val profiles = state.profiles
        when {
            !state.unlocked -> LockedCard(actions.unlock, first)
            profiles == null -> Loading()
            else -> {
                profiles.forEachIndexed { index, profile ->
                    TimeCard(
                        profile = profile,
                        texts = texts,
                        onQuota = { editing = TimeEdit(profile, quota = true) },
                        onSchedule = { editing = TimeEdit(profile, quota = false) },
                        first = first.takeIf { index == 0 },
                    )
                }
                InfoLine(ShashaIcons.Info, stringResource(R.string.hub_time_note))
            }
        }
    }
    val edit = editing ?: return
    val close = { editing = null }
    if (edit.quota) {
        OptionsDialog(
            title = stringResource(R.string.hub_time_quota_of, edit.profile.name),
            options = QuotaOptions.map { minutes ->
                (if (minutes == null) stringResource(R.string.quota_unlimited) else texts.duration(minutes.toLong())) to minutes
            },
            selected = edit.profile.dailyQuotaMinutes,
            onPick = { minutes ->
                close()
                actions.setQuota(edit.profile.id, minutes)
            },
            onDismiss = close,
        )
    } else {
        ScheduleDialog(
            title = stringResource(R.string.hub_time_schedule_of, edit.profile.name),
            schedule = edit.profile.schedule,
            texts = texts,
            onSave = { schedule ->
                close()
                actions.setSchedule(edit.profile.id, schedule)
            },
            onDismiss = close,
        )
    }
}

private data class TimeEdit(val profile: Profile, val quota: Boolean)

/** One profile's two values side by side (its quota on the right, its hours on the left), each a button. */
@Composable
private fun TimeCard(profile: Profile, texts: TimeTexts, onQuota: () -> Unit, onSchedule: () -> Unit, first: FocusRequester?) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("hub_time_${profile.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile.avatar, profile.name, 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            val accent = if (profile.isChild) Accents.Kids.bright else LocalAccent.current.bright
            Tag(stringResource(if (profile.isChild) R.string.profile_kind_child else R.string.profile_kind_adult), accent)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ValueButton(
                icon = ShashaIcons.Timer,
                label = stringResource(R.string.profile_quota),
                value = quotaText(profile.dailyQuotaMinutes, texts),
                onClick = onQuota,
                modifier = Modifier.weight(1f).let { if (first != null) it.focusRequester(first) else it }.paneItem("hub_quota_${profile.id}"),
            )
            ValueButton(
                icon = ShashaIcons.Calendar,
                label = stringResource(R.string.profile_schedule),
                value = scheduleText(profile, texts),
                onClick = onSchedule,
                modifier = Modifier.weight(1f).paneItem("hub_schedule_${profile.id}"),
            )
        }
    }
}

@Composable
private fun ValueButton(icon: ImageVector, label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.SurfaceVariant,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.03f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted, maxLines = 1)
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** «الإحصائيات»: today and this week for each profile; the details (7 days, top channels) are one OK away. */
@Composable
internal fun StatsPane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val texts = rememberTimeTexts()
    PaneColumn {
        val profiles = state.profiles
        when {
            !state.unlocked -> LockedCard(actions.unlock, first)
            profiles == null -> Loading()
            else -> {
                PaneRow(
                    title = stringResource(R.string.hub_stats_open),
                    onClick = actions.openStats,
                    modifier = Modifier.focusRequester(first).paneItem("hub_stats_open"),
                    icon = ShashaIcons.BarChart,
                    body = stringResource(R.string.hub_stats_open_body),
                )
                val usage = state.usage
                if (usage == null) {
                    Loading()
                } else {
                    profiles.forEach { profile -> StatsCard(profile, usage[profile.id], texts) }
                }
            }
        }
    }
}

/** Read-only (the focus passes over it so a long list scrolls): today, this week, and the change from last week. */
@Composable
private fun StatsCard(profile: Profile, summary: UsageSummary?, texts: TimeTexts) {
    ReadingCard(Modifier.paneItem("hub_stats_${profile.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(profile.avatar, profile.name, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(changeText(summary), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Figure(stringResource(R.string.stats_today), texts.durationShort(summary?.days?.lastOrNull()?.minutes ?: 0))
            Spacer(Modifier.width(8.dp))
            Figure(stringResource(R.string.stats_this_week), texts.durationShort(summary?.thisWeekMinutes ?: 0))
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    // One width for every card: the figures of the profiles line up under each other.
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun changeText(summary: UsageSummary?): String {
    val change = summary?.changePercent ?: return stringResource(R.string.stats_no_previous_week)
    // "22%" kept left to right inside the Arabic sentence (a directional isolate), like the stats screen.
    val percent = "\u2066${change.absoluteValue}%\u2069"
    return when {
        change > 0 -> stringResource(R.string.stats_more_than_last_week, percent)
        change < 0 -> stringResource(R.string.stats_less_than_last_week, percent)
        else -> stringResource(R.string.stats_same_as_last_week)
    }
}

@Composable
internal fun Loading() {
    ShashaLoaderWithText(stringResource(R.string.hub_loading), modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), size = 48.dp)
}

/** Attaches the pane's first focus to the element for which [condition] holds. */
internal fun Modifier.firstIf(condition: Boolean, first: FocusRequester): Modifier = if (condition) focusRequester(first) else this

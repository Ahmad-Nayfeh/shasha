package com.ahmadnayfeh.shasha.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons

/** The headings of the settings list, in its order. */
enum class SettingsGroup(@StringRes val label: Int) {
    PARENTAL(R.string.hub_group_parental),
    WATCHING(R.string.hub_group_watching),
    DEVICE(R.string.hub_group_device),
    APP(R.string.hub_group_app),
}

/**
 * SPEC §7's settings, one entry of the list each; the pane beside the list shows the focused one. [parental]:
 * its content stays behind the parent PIN (the adult profile may be left on while a child walks by); the
 * child profile has no settings at all (its navigation graph has no settings tab).
 */
enum class SettingsCategory(
    val group: SettingsGroup,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val icon: ImageVector,
    val parental: Boolean = false,
) {
    PROFILES(SettingsGroup.PARENTAL, R.string.hub_profiles, R.string.hub_profiles_summary, ShashaIcons.Group, parental = true),
    LISTS(SettingsGroup.PARENTAL, R.string.hub_lists, R.string.hub_lists_summary, SettingsIcons.Checklist, parental = true),
    TIME(SettingsGroup.PARENTAL, R.string.hub_time, R.string.hub_time_summary, ShashaIcons.Timer, parental = true),
    SERIOUS(SettingsGroup.PARENTAL, R.string.hub_serious, R.string.hub_serious_summary, ShashaIcons.Lock, parental = true),
    STATS(SettingsGroup.PARENTAL, R.string.settings_stats, R.string.stats_subtitle, ShashaIcons.BarChart, parental = true),
    SPONSORBLOCK(SettingsGroup.WATCHING, R.string.hub_sponsorblock, R.string.sponsor_settings_subtitle, ShashaIcons.SkipNext),
    QUALITY(SettingsGroup.WATCHING, R.string.hub_quality, R.string.hub_quality_summary, SettingsIcons.Hd),
    PLAYBACK(SettingsGroup.WATCHING, R.string.hub_playback, R.string.hub_playback_summary, ShashaIcons.Speed, parental = true),
    PROTECTION(SettingsGroup.DEVICE, R.string.settings_protection, R.string.protection_subtitle, ShashaIcons.Shield),
    REMOTE(SettingsGroup.DEVICE, R.string.hub_remote, R.string.hub_remote_summary, RemoteIcons.Phone),
    DATA(SettingsGroup.DEVICE, R.string.settings_backup, R.string.hub_data_summary, RemoteIcons.Backup),
    APPEARANCE(SettingsGroup.APP, R.string.hub_appearance, R.string.hub_appearance_summary, AppearanceIcons.Language),
    UPDATES(SettingsGroup.APP, R.string.action_check_updates, R.string.hub_updates_summary, ShashaIcons.SystemUpdate),
    DIAGNOSTICS(SettingsGroup.APP, R.string.settings_diagnostics, R.string.hub_diagnostics_summary, ShashaIcons.Build),
    RESET(SettingsGroup.APP, R.string.hub_reset, R.string.hub_reset_summary, ShashaIcons.Refresh, parental = true),
    GUIDE(SettingsGroup.APP, R.string.hub_guide, R.string.hub_guide_summary, AppearanceIcons.MenuBook),
    ABOUT(SettingsGroup.APP, R.string.hub_about, R.string.hub_about_summary, ShashaIcons.Info),
    ;

    /** Test tags: "settings_cat_<tag>" for the list entry, "settings_pane_<tag>" for its pane. */
    val tag: String get() = name.lowercase()
}

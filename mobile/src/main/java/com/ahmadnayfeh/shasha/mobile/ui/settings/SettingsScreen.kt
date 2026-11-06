package com.ahmadnayfeh.shasha.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.AppLanguage
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.AppTheme
import com.ahmadnayfeh.shasha.mobile.data.QualityChoices
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.mobile.ui.components.ConfirmDialog

/**
 * Settings: language, theme, playback (default quality, background playback, picture-in-picture), Shorts (on/off), comments (on/off), downloads
 * (quality) and SponsorBlock (on/off and its categories). Each group has «Reset», and so does the whole page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenAbout: () -> Unit, onOpenUpdate: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val defaults = remember { AppSettings() }
    var confirmResetAll by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(bottom = 24.dp)) {
            Section(stringResource(R.string.settings_language), resettable = settings.language != defaults.language, onReset = vm::resetLanguage) {
                ChoiceRow(stringResource(R.string.language_system), settings.language == AppLanguage.SYSTEM) { vm.setLanguage(AppLanguage.SYSTEM) }
                // Each language is named in itself, whatever the app's language is now.
                ChoiceRow("العربية", settings.language == AppLanguage.ARABIC) { vm.setLanguage(AppLanguage.ARABIC) }
                ChoiceRow("English", settings.language == AppLanguage.ENGLISH) { vm.setLanguage(AppLanguage.ENGLISH) }
            }

            Section(stringResource(R.string.settings_theme), resettable = settings.theme != defaults.theme, onReset = vm::resetTheme) {
                ChoiceRow(stringResource(R.string.theme_system), settings.theme == AppTheme.SYSTEM) { vm.setTheme(AppTheme.SYSTEM) }
                ChoiceRow(stringResource(R.string.theme_light), settings.theme == AppTheme.LIGHT) { vm.setTheme(AppTheme.LIGHT) }
                ChoiceRow(stringResource(R.string.theme_dark), settings.theme == AppTheme.DARK) { vm.setTheme(AppTheme.DARK) }
            }

            Section(
                stringResource(R.string.settings_default_quality),
                resettable = settings.defaultQuality != defaults.defaultQuality,
                onReset = vm::resetDefaultQuality,
                hint = stringResource(R.string.settings_default_quality_hint),
            ) {
                QualityChips(QualityChoices.PLAYBACK, settings.defaultQuality, vm::setDefaultQuality)
            }

            Section(stringResource(R.string.settings_playback), resettable = settings.backgroundPlayback != defaults.backgroundPlayback || settings.pictureInPicture != defaults.pictureInPicture, onReset = vm::resetPlayback) {
                SwitchRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_hint), settings.backgroundPlayback, vm::setBackgroundPlayback)
                SwitchRow(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_hint), settings.pictureInPicture, vm::setPictureInPicture)
            }

            Section(
                stringResource(R.string.settings_shorts),
                resettable = settings.showShorts != defaults.showShorts,
                onReset = vm::resetShorts,
                hint = stringResource(R.string.settings_shorts_hint),
            ) {
                SwitchRow(stringResource(R.string.settings_shorts_show), null, settings.showShorts, vm::setShowShorts)
            }

            Section(
                stringResource(R.string.settings_comments),
                resettable = settings.showComments != defaults.showComments || settings.commentSort != defaults.commentSort,
                onReset = vm::resetComments,
                hint = stringResource(R.string.settings_comments_hint),
            ) {
                SwitchRow(stringResource(R.string.settings_comments_show), null, settings.showComments, vm::setShowComments)
            }

            Section(
                stringResource(R.string.settings_download_quality),
                resettable = settings.downloadQuality != defaults.downloadQuality,
                onReset = vm::resetDownloadQuality,
                hint = stringResource(R.string.settings_download_quality_hint),
            ) {
                QualityChips(QualityChoices.DOWNLOAD, settings.downloadQuality, vm::setDownloadQuality)
            }

            Section(
                stringResource(R.string.settings_sponsorblock),
                resettable = settings.sponsorBlockEnabled != defaults.sponsorBlockEnabled || settings.sponsorCategories != defaults.sponsorCategories,
                onReset = vm::resetSponsorBlock,
                hint = stringResource(R.string.settings_sponsorblock_hint),
            ) {
                SwitchRow(stringResource(R.string.settings_sponsorblock_enabled), null, settings.sponsorBlockEnabled, vm::setSponsorBlockEnabled)
                if (settings.sponsorBlockEnabled) {
                    for (category in SponsorCategory.entries) {
                        SwitchRow(
                            stringResource(category.label),
                            stringResource(category.description),
                            category in settings.sponsorCategories,
                        ) { vm.setSponsorCategory(category, it) }
                    }
                }
                Text(
                    stringResource(R.string.sponsorblock_attribution_short),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenUpdate),
                leadingContent = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.update_title)) },
                supportingContent = { Text(stringResource(R.string.update_summary)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenAbout),
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.about_title)) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
            OutlinedButton(onClick = { confirmResetAll = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_reset_all))
            }
        }
    }
    if (confirmResetAll) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_all),
            body = stringResource(R.string.settings_reset_all_body),
            confirmLabel = stringResource(R.string.action_reset),
            onConfirm = vm::resetAll,
            onDismiss = { confirmResetAll = false },
        )
    }
}

/** A group of settings with its title, its «Reset» (enabled when something differs from the default) and a hint. */
@Composable
private fun Section(title: String, resettable: Boolean, onReset: () -> Unit, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onReset, enabled = resettable) { Text(stringResource(R.string.action_reset)) }
        }
        if (hint != null) {
            Text(hint, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** 0 is «Auto»; the others are heights ("1080p"). */
@Composable
private fun QualityChips(choices: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices) { height ->
            FilterChip(
                selected = height == selected,
                onClick = { onSelect(height) },
                label = { Text(if (height == 0) stringResource(R.string.quality_auto) else "${height}p") },
            )
        }
    }
}

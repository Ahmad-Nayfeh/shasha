package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.AppTheme
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.screens.settings.SectionLabel

/**
 * «المظهر واللغة»: the app's language (each named in its own script, so it can be found in the other one) and its
 * theme. Not behind the parent PIN: neither is a rule. A new language recreates the screens at once (MainActivity);
 * the theme repaints them in place. The voice search keeps its own language («التشغيل والتنبيهات»).
 */
@Composable
internal fun AppearancePane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    val behavior = state.behavior ?: Behavior.DEFAULT
    val default = stringResource(R.string.behavior_default_tag)
    PaneColumn {
        SectionLabel(stringResource(R.string.appearance_language), icon = AppearanceIcons.Language)
        ChoiceRow(
            title = stringResource(R.string.language_name_ar),
            body = stringResource(R.string.appearance_language_ar_body),
            selected = behavior.appLanguage == AppLanguage.ARABIC,
            onClick = { actions.setAppLanguage(AppLanguage.ARABIC) },
            modifier = Modifier.firstIf(true, first).paneItem("appearance_language_ar"),
            badge = default.takeIf { AppLanguage.DEFAULT == AppLanguage.ARABIC },
        )
        ChoiceRow(
            title = stringResource(R.string.language_name_en),
            body = stringResource(R.string.appearance_language_en_body),
            selected = behavior.appLanguage == AppLanguage.ENGLISH,
            onClick = { actions.setAppLanguage(AppLanguage.ENGLISH) },
            modifier = Modifier.paneItem("appearance_language_en"),
            badge = default.takeIf { AppLanguage.DEFAULT == AppLanguage.ENGLISH },
        )
        InfoLine(ShashaIcons.Info, stringResource(R.string.appearance_language_note))
        Spacer(Modifier.height(6.dp))
        SectionLabel(stringResource(R.string.appearance_theme), icon = AppearanceIcons.Palette)
        ChoiceRow(
            title = stringResource(R.string.appearance_theme_dark),
            body = stringResource(R.string.appearance_theme_dark_body),
            selected = behavior.appTheme == AppTheme.DARK,
            onClick = { actions.setAppTheme(AppTheme.DARK) },
            modifier = Modifier.paneItem("appearance_theme_dark"),
            badge = default.takeIf { AppTheme.DEFAULT == AppTheme.DARK },
        )
        ChoiceRow(
            title = stringResource(R.string.appearance_theme_light),
            body = stringResource(R.string.appearance_theme_light_body),
            selected = behavior.appTheme == AppTheme.LIGHT,
            onClick = { actions.setAppTheme(AppTheme.LIGHT) },
            modifier = Modifier.paneItem("appearance_theme_light"),
            badge = default.takeIf { AppTheme.DEFAULT == AppTheme.LIGHT },
        )
    }
}

/**
 * «الدليل والفيديو»: «دليل شاشة»'s pages and the explainer video, both in the app's language, full screen. The video
 * row says so calmly when this build has no video ([SettingsHubState.explainerAvailable]); it still opens, to explain.
 */
@Composable
internal fun GuidePane(state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    PaneColumn {
        PaneRow(
            title = stringResource(R.string.guide_open),
            onClick = actions.openGuide,
            modifier = Modifier.firstIf(true, first).paneItem("guide_open"),
            icon = AppearanceIcons.MenuBook,
            body = stringResource(R.string.guide_open_body),
        )
        PaneRow(
            title = stringResource(R.string.explainer_open),
            onClick = actions.openExplainer,
            modifier = Modifier.paneItem("explainer_open"),
            icon = AppearanceIcons.SmartDisplay,
            body = stringResource(R.string.explainer_open_body),
            value = stringResource(R.string.explainer_unavailable).takeUnless { state.explainerAvailable },
        )
        InfoLine(ShashaIcons.Info, stringResource(R.string.guide_language_note))
    }
}

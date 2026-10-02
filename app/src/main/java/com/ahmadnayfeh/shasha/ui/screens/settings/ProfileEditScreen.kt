package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.DeleteProfileResult
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.Avatars
import com.ahmadnayfeh.shasha.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.LocalBehavior
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShortsGlyph
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.launch

private enum class EditDialog { NAME, AVATAR, QUOTA, SCHEDULE, DELETE }

@Composable
fun ProfileEditRoute(profileId: String, onOpenAllowlist: (String) -> Unit, onBack: () -> Unit) {
    val vm = containerViewModel("profile:$profileId") { ProfileEditViewModel(profileId, it.profiles) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val lastAdult by vm.isLastAdult.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    var seen by remember { mutableStateOf(false) }
    // Gone after it was shown (deleted here, or from the phone): nothing left to edit.
    LaunchedEffect(profile) {
        if (profile != null) seen = true else if (seen) onBack()
    }
    val shown = profile ?: return
    ProfileEditScreen(
        profile = shown,
        canDelete = !(shown.isAdult && lastAdult),
        onUpdate = vm::update,
        onOpenAllowlist = { onOpenAllowlist(profileId) },
        onDelete = {
            scope.launch {
                if (vm.delete() == DeleteProfileResult.Deleted) {
                    messages?.show(resources.getString(R.string.profile_deleted, shown.name))
                }
            }
        },
    )
}

/**
 * One profile's rules (SPEC §3): name and picture, the daily quota, the allowed hours, «إظهار مقاطع Shorts» and
 * «إظهار الفيديوهات المقترحة» (each saying what it does), and for a child the allowlist. Each change is stored at once.
 * The last adult profile cannot be deleted.
 */
@Composable
fun ProfileEditScreen(
    profile: Profile,
    canDelete: Boolean,
    onUpdate: ((Profile) -> Profile) -> Unit,
    onOpenAllowlist: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    initialDialog: Int = -1,
) {
    val texts = rememberTimeTexts()
    var dialog by rememberSaveable { mutableStateOf(EditDialog.entries.getOrNull(initialDialog)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    CompositionLocalProvider(LocalAccent provides if (profile.isChild) Accents.Kids else Accents.Teal) {
        Row(modifier.fillMaxSize().padding(ShashaDimens.ScreenPadding).testTag("profile_edit")) {
            Column(Modifier.width(260.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                ProfileAvatar(profile.avatar, profile.name, 132.dp)
                Spacer(Modifier.height(18.dp))
                Text(profile.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2)
                Text(
                    stringResource(if (profile.isChild) R.string.profile_kind_child else R.string.profile_kind_adult),
                    style = MaterialTheme.typography.titleSmall,
                    color = LocalAccent.current.bright,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(if (profile.isChild) R.string.profile_edit_child_note else R.string.profile_edit_adult_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = ShashaColors.Muted,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.width(36.dp))
            Column(
                // Padding inside the scroll: the focused row's zoom is not cut at the top or bottom.
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 18.dp).focusRestorer(first).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingRow(ShashaIcons.Edit, stringResource(R.string.profile_name), profile.name, { dialog = EditDialog.NAME }, Modifier.focusRequester(first).testTag("edit_name"))
                SettingRow(ShashaIcons.Person, stringResource(R.string.profile_avatar), null, { dialog = EditDialog.AVATAR }, Modifier.testTag("edit_avatar")) {
                    ProfileAvatar(profile.avatar, profile.name, 38.dp)
                }
                SettingRow(ShashaIcons.Timer, stringResource(R.string.profile_quota), quotaText(profile.dailyQuotaMinutes, texts), { dialog = EditDialog.QUOTA }, Modifier.testTag("edit_quota"))
                SettingRow(ShashaIcons.Calendar, stringResource(R.string.profile_schedule), scheduleText(profile, texts), { dialog = EditDialog.SCHEDULE }, Modifier.testTag("edit_schedule"))
                ToggleRow(
                    ShortsGlyph,
                    stringResource(R.string.profile_show_shorts),
                    !profile.hideShorts,
                    { onUpdate { it.copy(hideShorts = !it.hideShorts) } },
                    Modifier.testTag("edit_shorts"),
                    description = stringResource(R.string.profile_show_shorts_body, shortsLimitText(LocalBehavior.current.shortsMaxSeconds, texts)),
                )
                ToggleRow(
                    ShashaIcons.PlaylistPlay,
                    stringResource(R.string.profile_show_suggestions),
                    profile.showSuggestions,
                    { onUpdate { it.copy(showSuggestions = !it.showSuggestions) } },
                    Modifier.testTag("edit_suggestions"),
                    description = stringResource(
                        if (profile.isChild) R.string.profile_show_suggestions_body_child else R.string.profile_show_suggestions_body,
                    ),
                )
                if (profile.isAdult) {
                    ToggleRow(
                        ShashaIcons.VisibilityOff,
                        stringResource(R.string.profile_hide_comments),
                        profile.hideComments,
                        { onUpdate { it.copy(hideComments = !it.hideComments) } },
                        Modifier.testTag("edit_comments"),
                    )
                } else {
                    Text(
                        stringResource(R.string.profile_comments_child_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShashaColors.Muted,
                        modifier = Modifier.padding(horizontal = 18.dp).testTag("edit_comments_note"),
                    )
                }
                if (profile.isChild) {
                    SettingRow(ShashaIcons.Shield, stringResource(R.string.profile_allowlist), allowlistText(profile), onOpenAllowlist, Modifier.testTag("edit_allowlist"))
                }
                if (canDelete) {
                    Spacer(Modifier.height(6.dp))
                    SettingRow(ShashaIcons.Delete, stringResource(R.string.profile_delete), null, { dialog = EditDialog.DELETE }, Modifier.testTag("edit_delete"))
                }
            }
        }
        EditDialogs(dialog, profile, texts, onUpdate, onDelete, onClose = { dialog = null })
    }
}

/** «3 دقائق», «90 ثانية»: the parent's short-video limit («حدّ الفيديو القصير»), as the Shorts switch explains it. */
@Composable
private fun shortsLimitText(seconds: Int, texts: TimeTexts): String =
    if (seconds % 60 == 0) texts.duration(seconds / 60L) else rememberFormats().count(R.array.count_seconds, seconds.toLong())

@Composable
private fun EditDialogs(
    dialog: EditDialog?,
    profile: Profile,
    texts: TimeTexts,
    onUpdate: ((Profile) -> Profile) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        EditDialog.NAME -> NameDialog(
            title = stringResource(R.string.profile_name),
            initial = profile.name,
            hint = stringResource(if (profile.isChild) R.string.setup_child_hint else R.string.setup_owner_hint),
            onSave = { name ->
                onClose()
                onUpdate { it.copy(name = name) }
            },
            onDismiss = onClose,
        )
        EditDialog.AVATAR -> AvatarDialog(
            title = stringResource(R.string.profile_avatar),
            keys = if (profile.isChild) Avatars.childKeys else Avatars.keys,
            selected = profile.avatar,
            name = profile.name,
            onPick = { avatar ->
                onClose()
                onUpdate { it.copy(avatar = avatar) }
            },
            onDismiss = onClose,
        )
        EditDialog.QUOTA -> OptionsDialog(
            title = stringResource(R.string.profile_quota),
            options = QuotaOptions.map { minutes ->
                (if (minutes == null) stringResource(R.string.quota_unlimited) else texts.duration(minutes.toLong())) to minutes
            },
            selected = profile.dailyQuotaMinutes,
            onPick = { minutes ->
                onClose()
                onUpdate { it.copy(dailyQuotaMinutes = minutes) }
            },
            onDismiss = onClose,
        )
        EditDialog.SCHEDULE -> ScheduleDialog(
            title = stringResource(R.string.profile_schedule),
            schedule = profile.schedule,
            texts = texts,
            onSave = { schedule ->
                onClose()
                onUpdate { it.copy(schedule = schedule) }
            },
            onDismiss = onClose,
        )
        EditDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.profile_delete_title, profile.name),
            body = stringResource(R.string.profile_delete_body),
            confirmLabel = stringResource(R.string.profile_delete),
            confirmIcon = ShashaIcons.Delete,
            onConfirm = {
                onClose()
                onDelete()
            },
            onDismiss = onClose,
        )
    }
}

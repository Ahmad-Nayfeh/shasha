package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.AvatarChooser
import com.ahmadnayfeh.shasha.ui.components.Avatars
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaTextField
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.format.TimeTexts
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.launch

@Composable
fun ProfilesRoute(onOpenProfile: (String) -> Unit) {
    val vm = containerViewModel { ProfilesViewModel(it.profiles) }
    ProfilesContent(vm, onOpenProfile)
}

/**
 * The profiles with «إضافة ملف طفل»'s dialog: the new child is stored, then its page opens (where its rules are set). A
 * profile that could not be stored says so and the dialog stays, with what was typed.
 */
@Composable
internal fun ProfilesContent(vm: ProfilesViewModel, onOpenProfile: (String) -> Unit) {
    val all by vm.all.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    ProfilesScreen(all, focus = remember { ContentFocus() }, onOpenProfile = onOpenProfile, onAddChild = { creating = true })
    if (creating) {
        NewChildDialog(
            onCreate = { name, avatar ->
                // One profile per press: a second OK while it is being stored adds no twin.
                if (!saving) {
                    saving = true
                    scope.launch {
                        val child = vm.createChild(name, avatar)
                        saving = false
                        if (child != null) {
                            creating = false
                            onOpenProfile(child.id)
                        } else {
                            messages?.show(resources.getString(R.string.profiles_create_failed))
                        }
                    }
                }
            },
            onDismiss = { creating = false },
        )
    }
}

/** الملفات: every profile with its rules at a glance; OK opens one. «إضافة ملف طفل» sits in the header. */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>?,
    focus: ContentFocus,
    onOpenProfile: (String) -> Unit,
    onAddChild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberFormats()
    val texts = rememberTimeTexts()
    TakeFocusWhenReady(focus, ready = profiles != null)
    if (profiles == null) return
    val gridState = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    ShashaGrid(
        gridState,
        focus,
        firstCard,
        modifier.fillMaxSize().testTag("profiles"),
        contentPadding = PaddingValues(horizontal = ShashaDimens.ScreenHorizontal, vertical = 40.dp),
        minCellWidth = 250.dp,
    ) {
        fullWidthItem("header") {
            ScreenHeader(stringResource(R.string.profiles_title), formats.count(R.array.count_profiles, profiles.size.toLong())) {
                // Always in sight, however many profiles there are; the kids yellow says what it adds.
                KidsAccent {
                    ShashaButton(
                        stringResource(R.string.profiles_add_child),
                        onAddChild,
                        icon = ShashaIcons.PersonAdd,
                        modifier = Modifier.testTag("profiles_add_child"),
                    )
                }
            }
        }
        itemsIndexed(profiles, key = { _, it -> it.id }, contentType = { _, _ -> "profile" }) { index, profile ->
            ProfileTile(
                profile,
                texts,
                onClick = { onOpenProfile(profile.id) },
                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
            )
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, texts: TimeTexts, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = if (profile.isChild) Accents.Kids else Accents.Teal
    CompositionLocalProvider(LocalAccent provides accent) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(208.dp).testTag("profile_${profile.id}"),
            shape = RoundedCornerShape(24.dp),
            containerColor = ShashaColors.Surface,
            focusedContainerColor = ShashaColors.SurfaceHigh,
            scale = 1.04f,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile.avatar, profile.name, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        KindPill(profile)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Fact(ShashaIcons.Timer, quotaText(profile.dailyQuotaMinutes, texts))
                Spacer(Modifier.height(6.dp))
                Fact(ShashaIcons.Calendar, scheduleText(profile, texts))
                if (profile.isChild) {
                    Spacer(Modifier.height(6.dp))
                    Fact(ShashaIcons.Shield, allowlistText(profile))
                }
            }
        }
    }
}

@Composable
private fun KindPill(profile: Profile) {
    val accent = LocalAccent.current
    Text(
        stringResource(if (profile.isChild) R.string.profile_kind_child else R.string.profile_kind_adult),
        style = MaterialTheme.typography.labelMedium,
        color = accent.bright,
        modifier = Modifier
            .padding(top = 4.dp)
            .background(accent.tint(0.14f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Text.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A new child profile: a name (the TV's keyboard: this is the parent's settings) and a picture. The focus starts on the name's
 * box, which opens the keyboard only on OK; the keyboard's action key goes on to «إنشاء». «إنشاء» without a name says so under
 * the box and goes back to it.
 */
@Composable
fun NewChildDialog(onCreate: (name: String, avatar: String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableStateOf(Profile.DEFAULT_CHILD_AVATAR) }
    var missingName by rememberSaveable { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val create = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KidsAccent {
            DialogCard(stringResource(R.string.profiles_new_child_title), Modifier.testTag("new_child_dialog"), width = 640.dp) {
                Text(
                    stringResource(R.string.profiles_new_child_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShashaColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                ShashaTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        missingName = false
                    },
                    hint = stringResource(R.string.setup_child_hint),
                    icon = ShashaIcons.Edit,
                    maxLength = PROFILE_NAME_MAX,
                    onDone = { runCatching { create.requestFocus() } },
                    modifier = Modifier.width(420.dp).focusRequester(field).testTag("new_child_name"),
                )
                if (missingName) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.profiles_name_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShashaColors.Error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("new_child_name_missing"),
                    )
                }
                Spacer(Modifier.height(18.dp))
                AvatarChooser(Avatars.childKeys, avatar, { avatar = it }, name, size = 54.dp)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ShashaButton(
                        stringResource(R.string.profiles_create),
                        {
                            if (name.isNotBlank()) {
                                onCreate(name.trim(), avatar)
                            } else {
                                missingName = true
                                runCatching { field.requestFocus() }
                            }
                        },
                        icon = ShashaIcons.Check,
                        primary = true,
                        modifier = Modifier.focusRequester(create).testTag("new_child_create"),
                    )
                    ShashaButton(stringResource(R.string.action_cancel), onDismiss, modifier = Modifier.testTag("new_child_cancel"))
                }
            }
        }
    }
}

@Composable
fun quotaText(minutes: Int?, texts: TimeTexts): String =
    if (minutes == null) stringResource(R.string.quota_unlimited) else stringResource(R.string.quota_per_day, texts.duration(minutes.toLong()))

@Composable
fun scheduleText(profile: Profile, texts: TimeTexts): String {
    val schedule = profile.schedule?.takeUnless { it.isAlwaysAllowed } ?: return stringResource(R.string.schedule_all_day)
    return stringResource(R.string.schedule_range, texts.time(schedule.allowedFromMinute), texts.time(schedule.allowedUntilMinute))
}

@Composable
fun allowlistText(profile: Profile): String {
    val formats = rememberFormats()
    val parts = listOfNotNull(
        profile.allowlist.channels.size.takeIf { it > 0 }?.let { formats.count(R.array.count_channels, it.toLong()) },
        profile.allowlist.playlists.size.takeIf { it > 0 }?.let { formats.count(R.array.count_playlists, it.toLong()) },
    )
    return if (parts.isEmpty()) stringResource(R.string.allowlist_empty_short) else parts.joinToString("  ·  ")
}

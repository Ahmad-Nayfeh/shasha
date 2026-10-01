package com.ahmadnayfeh.shasha.ui.screens.profiles

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.app.PickerMode
import com.ahmadnayfeh.shasha.ui.app.RootViewModel
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinDialog
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ahmadnayfeh.shasha.ui.components.brandLockup

/**
 * «من يشاهد الآن؟»: at start (when there are child profiles) and on every switch. A child's card enters
 * directly; the adult's asks for the parent PIN, as does leaving a child profile. BACK returns to the
 * profile the picker was opened from; after a child's «خروج» it also does so by itself after a minute
 * without a choice, so a parent who walks away does not leave the TV unlocked.
 */
@Composable
fun ProfilePickerRoute(vm: RootViewModel, mode: PickerMode) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var activity by remember { mutableIntStateOf(0) }

    BackHandler(enabled = mode != PickerMode.START) { vm.closePicker() }
    if (mode == PickerMode.FROM_CHILD_EXIT) {
        LaunchedEffect(activity, pinFor) {
            if (pinFor == null) {
                delay(IDLE_RETURN_MS)
                vm.closePicker()
            }
        }
    }
    ProfilePickerScreen(
        profiles = profiles,
        currentId = current?.id,
        locked = { vm.needsPin(it) },
        mode = mode,
        onChoose = { target ->
            if (vm.needsPin(target)) {
                pinFor = target
            } else {
                scope.launch {
                    val result = vm.choose(target, pin = null)
                    if (result is PinCheck.Refused) messages?.show(resources.getString(result.message), ShashaIcons.Info)
                }
            }
        },
        modifier = Modifier.onPreviewKeyEvent {
            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) activity++
            false
        },
    )
    pinFor?.let { target ->
        PinDialog(onDismiss = { pinFor = null }) {
            ParentPinPad(
                title = stringResource(R.string.pin_parent_title),
                subtitle = stringResource(R.string.pin_enter_body, target.name),
                check = { pin -> vm.choose(target, pin) },
                onAccepted = { pinFor = null },
                initialLockMillis = vm::pinLockMillis,
                header = { ProfileAvatar(target.avatar, target.name, 64.dp) },
            )
        }
    }
}

/** The picker as drawn (for screenshots and tests: no ViewModel). */
@Composable
fun ProfilePickerScreen(
    profiles: List<Profile>,
    currentId: String?,
    locked: (Profile) -> Boolean,
    mode: PickerMode,
    onChoose: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember { FocusRequester() }
    val focusIndex = profiles.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isNotEmpty()) {
            withFrameNanos { }
            runCatching { initial.requestFocus() }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(ShashaColors.Primary.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.52f),
                        radius = size.width * 0.5f,
                    ),
                )
            }
            .padding(ShashaDimens.ScreenPadding)
            .testTag("profile_picker"),
    ) {
        // The brand where reading starts (top right in Arabic), clear of the centred question.
        Image(
            painterResource(brandLockup()),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).height(34.dp),
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(44.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusRestorer(initial),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            ) {
                itemsIndexed(profiles, key = { _, it -> it.id }) { index, profile ->
                    ProfileCard(
                        profile = profile,
                        locked = locked(profile),
                        current = profile.id == currentId && mode != PickerMode.START,
                        onClick = { onChoose(profile) },
                        modifier = if (index == focusIndex) Modifier.focusRequester(initial) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(
                stringResource(
                    when (mode) {
                        PickerMode.START -> R.string.picker_hint_start
                        PickerMode.FROM_ADULT -> R.string.picker_hint_from_adult
                        PickerMode.FROM_CHILD_EXIT -> R.string.picker_hint_from_child
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One profile: its avatar (the kids yellow ring for a child, teal for the adult), name, and how it opens. */
@Composable
private fun ProfileCard(profile: Profile, locked: Boolean, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = if (profile.isChild) Accents.Kids else Accents.Teal
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    CompositionLocalProvider(LocalAccent provides accent) {
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = onClick,
                modifier = modifier.size(AvatarSize).testTag("picker_${profile.id}"),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = ShashaColors.Surface, focusedContainerColor = ShashaColors.Surface),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(2.dp, ShashaColors.Outline), inset = 0.dp, shape = CircleShape),
                    focusedBorder = Border(BorderStroke(5.dp, accent.main), inset = 0.dp, shape = CircleShape),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.cardGlow),
                interactionSource = interaction,
            ) {
                ProfileAvatar(profile.avatar, profile.name, AvatarSize, Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                profile.name,
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) ShashaColors.Text else ShashaColors.Text.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            CardCaption(locked, current, profile.isChild)
        }
    }
}

@Composable
private fun CardCaption(locked: Boolean, current: Boolean, child: Boolean) {
    val shape = RoundedCornerShape(percent = 50)
    val (icon, text) = when {
        current -> ShashaIcons.CheckCircle to stringResource(R.string.picker_current)
        locked -> ShashaIcons.Lock to stringResource(R.string.picker_with_pin)
        child -> null to stringResource(R.string.picker_child)
        else -> null to stringResource(R.string.picker_adult)
    }
    Row(
        Modifier
            .background(ShashaColors.Surface.copy(alpha = 0.8f), shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = LocalAccent.current.bright, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
    }
}

private val AvatarSize = 132.dp
private const val IDLE_RETURN_MS = 60_000L

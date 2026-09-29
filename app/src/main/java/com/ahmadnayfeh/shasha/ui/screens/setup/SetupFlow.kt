package com.ahmadnayfeh.shasha.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinSetupResult
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.AvatarChooser
import com.ahmadnayfeh.shasha.ui.components.Avatars
import com.ahmadnayfeh.shasha.ui.components.NewPinPad
import com.ahmadnayfeh.shasha.ui.components.PinPanel
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaTextField
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SetupStep { WELCOME, PIN, OWNER, CHILD }

/**
 * The first start (no parent PIN yet): welcome, the parent PIN (typed twice on the app's own keypad),
 * the owner's name and picture, and optionally a first child profile. Done by the parent, so the TV's
 * keyboard is fine for the names. [onDone] follows.
 */
class SetupViewModel(private val profiles: ProfileRepository, private val pin: ParentPinRepository) : ViewModel() {
    private val _step = MutableStateFlow(SetupStep.WELCOME)
    val step: StateFlow<SetupStep> = _step.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _owner = MutableStateFlow<Profile?>(null)

    /** The owner's profile as stored (its current name and avatar prefill the form). */
    val owner: StateFlow<Profile?> = _owner.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch { _owner.value = profiles.all().firstOrNull { it.isAdult } }
    }

    fun start() {
        _step.value = SetupStep.PIN
    }

    fun back() {
        _step.value = when (_step.value) {
            SetupStep.PIN -> SetupStep.WELCOME
            SetupStep.CHILD -> SetupStep.OWNER
            else -> _step.value
        }
    }

    fun choosePin(value: String) = work {
        // AlreadySet: another screen was quicker (a restart mid-setup); the stored PIN stays.
        if (pin.setup(value) != PinSetupResult.InvalidFormat) _step.value = SetupStep.OWNER
    }

    fun saveOwner(name: String, avatar: String) = work {
        val id = _owner.value?.id ?: ADULT_PROFILE_ID
        profiles.update(id) { it.copy(name = name.trim().ifEmpty { it.name }, avatar = avatar) }
        _step.value = SetupStep.CHILD
    }

    fun addChild(name: String, avatar: String) = work {
        val household = ParentalLists.householdBlocklist(profiles.all())
        profiles.upsert(Profile.newChild(name.trim(), avatar = avatar).copy(blocklist = household))
        _done.value = true
    }

    fun skipChild() {
        _done.value = true
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun SetupRoute(profiles: ProfileRepository, pin: ParentPinRepository, onDone: () -> Unit) {
    val vm: SetupViewModel = viewModel(factory = viewModelFactory { initializer { SetupViewModel(profiles, pin) } })
    val step by vm.step.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val owner by vm.owner.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) onDone() }
    BackHandler(enabled = step == SetupStep.PIN || step == SetupStep.CHILD) { vm.back() }
    SetupScreen(
        step = step,
        busy = busy,
        ownerName = owner?.name.orEmpty(),
        ownerAvatar = owner?.avatar ?: Profile.DEFAULT_ADULT_AVATAR,
        onStart = vm::start,
        onPin = vm::choosePin,
        onOwner = vm::saveOwner,
        onChild = vm::addChild,
        onSkipChild = vm::skipChild,
    )
}

@Composable
fun SetupScreen(
    step: SetupStep,
    busy: Boolean,
    ownerName: String,
    ownerAvatar: String,
    onStart: () -> Unit,
    onPin: (String) -> Unit,
    onOwner: (name: String, avatar: String) -> Unit,
    onChild: (name: String, avatar: String) -> Unit,
    onSkipChild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(ShashaColors.Primary.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.35f),
                        radius = size.width * 0.45f,
                    ),
                )
            }
            .padding(ShashaDimens.ScreenPadding)
            .testTag("setup"),
    ) {
        // The step's content above the dots, never over them.
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The Box lets each step keep its own size, centred in the room above the dots.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { (fadeIn(tween(320, delayMillis = 80)) + slideInHorizontally(tween(320)) { -it / 12 }) togetherWith fadeOut(tween(160)) },
                    contentAlignment = Alignment.Center,
                    label = "setupStep",
                ) { shown ->
                    when (shown) {
                        SetupStep.WELCOME -> Welcome(onStart)
                        // Compact: the room above the step dots is 422dp, the usual card 446dp.
                        SetupStep.PIN -> PinPanel(compact = true) {
                            NewPinPad(
                                title = stringResource(R.string.setup_pin_title),
                                subtitle = stringResource(R.string.setup_pin_body),
                                onChosen = onPin,
                                busy = busy,
                            )
                        }
                        SetupStep.OWNER -> NameAndAvatar(
                            title = stringResource(R.string.setup_owner_title),
                            body = stringResource(R.string.setup_owner_body),
                            initialName = ownerName,
                            initialAvatar = ownerAvatar,
                            avatars = Avatars.keys,
                            hint = stringResource(R.string.setup_owner_hint),
                            primary = stringResource(R.string.setup_next),
                            onPrimary = onOwner,
                            busy = busy,
                        )
                        SetupStep.CHILD -> KidsAccent {
                            NameAndAvatar(
                                title = stringResource(R.string.setup_child_title),
                                body = stringResource(R.string.setup_child_body),
                                initialName = "",
                                initialAvatar = Profile.DEFAULT_CHILD_AVATAR,
                                avatars = Avatars.childKeys,
                                hint = stringResource(R.string.setup_child_hint),
                                primary = stringResource(R.string.setup_child_add),
                                onPrimary = onChild,
                                busy = busy,
                                secondary = stringResource(R.string.setup_child_skip),
                                onSecondary = onSkipChild,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            StepDots(step)
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit) {
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { start.requestFocus() }
    }
    Column(Modifier.widthIn(max = 760.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.splash_lockup), contentDescription = null, modifier = Modifier.height(54.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.setup_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Feature(ShashaIcons.Shield, stringResource(R.string.setup_feature_kids))
            Feature(ShashaIcons.Timer, stringResource(R.string.setup_feature_time))
            Feature(ShashaIcons.Lock, stringResource(R.string.setup_feature_pin))
        }
        Spacer(Modifier.height(28.dp))
        ShashaButton(
            stringResource(R.string.setup_start),
            onStart,
            icon = ShashaIcons.Chevron,
            primary = true,
            modifier = Modifier.focusRequester(start).testTag("setup_start"),
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .width(220.dp)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).background(ShashaColors.Primary.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

/** A name on the TV's keyboard and a picture: the owner's profile, or a first child's. */
@Composable
private fun NameAndAvatar(
    title: String,
    body: String,
    initialName: String,
    initialAvatar: String,
    avatars: List<String>,
    hint: String,
    primary: String,
    onPrimary: (name: String, avatar: String) -> Unit,
    busy: Boolean,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var avatar by rememberSaveable(initialAvatar) { mutableStateOf(initialAvatar) }
    val field = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    // The name first while there is none; the restorer's fallback must agree, or it takes the focus there.
    val start = if (initialName.isEmpty()) field else action
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { start.requestFocus() }
    }
    val ready = name.isNotBlank() && !busy
    Column(Modifier.width(720.dp).focusRestorer(start).focusGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(26.dp))
        ShashaTextField(
            value = name,
            onValueChange = { name = it },
            hint = hint,
            icon = ShashaIcons.Edit,
            maxLength = NAME_MAX,
            onDone = { runCatching { action.requestFocus() } },
            modifier = Modifier.width(460.dp).focusRequester(field).testTag("setup_name"),
        )
        Spacer(Modifier.height(24.dp))
        AvatarChooser(avatars, avatar, { avatar = it }, name, size = 58.dp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShashaButton(
                primary,
                { if (ready) onPrimary(name, avatar) else runCatching { field.requestFocus() } },
                icon = ShashaIcons.Check,
                primary = true,
                modifier = Modifier.focusRequester(action).testTag("setup_primary"),
            )
            if (secondary != null) ShashaButton(secondary, onSecondary, modifier = Modifier.testTag("setup_secondary"))
        }
    }
}

@Composable
private fun StepDots(step: SetupStep, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SetupStep.entries.forEach {
            val on = it.ordinal <= step.ordinal
            Box(
                Modifier
                    .size(width = if (it == step) 28.dp else 10.dp, height = 10.dp)
                    .background(if (on) ShashaColors.Primary else ShashaColors.Outline, RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** Long names do not fit the picker's cards. */
const val NAME_MAX = 20

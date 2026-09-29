package com.ahmadnayfeh.shasha.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.ui.components.BrandMark
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.MarkGlyph
import com.ahmadnayfeh.shasha.ui.components.NightColors
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinPanel
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.TimeColors
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** What the parent gives after the PIN on the "time up" screens. */
enum class UnlockChoice(val minutes: Int?) {
    PLUS_15(15),
    PLUS_30(30),
    PLUS_60(60),

    /** No limit until the end of the day (the phone's «فتح انتهى الوقت» without minutes). */
    REST_OF_TODAY(null),
}

/**
 * The parent's side of the "time up" screens: the PIN (through the app's one attempt limiter), the grant,
 * or another profile instead (the parent sits down to watch: the child gets no extra time).
 */
interface TimeUnlock {
    suspend fun check(pin: String): PinCheck

    /** How long PIN checks are locked out right now (0 when they are not). */
    suspend fun lockMillis(): Long

    suspend fun grant(choice: UnlockChoice)

    /** Leaves this profile for the profile picker, with the [pin] just given. */
    suspend fun switchProfile(pin: String): PinCheck
}

/**
 * SPEC §3: once the day's time is used up, or outside the allowed hours, [content] (every screen of the
 * profile, the player included) makes way for a full-screen notice that BACK cannot dismiss; only the
 * parent PIN opens it again (here, or from the phone). [content]'s state is kept meanwhile: after the
 * unlock the viewer is back where they were, and a video continues where it stopped.
 *
 * [state] is only trusted once [ready] (before that the session is a stand-in and nothing is blocked).
 */
@Composable
fun SessionGate(
    state: SessionState,
    ready: Boolean,
    profile: Profile,
    unlock: TimeUnlock,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    content: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val blocked = ready && !state.canWatch
    Box(modifier.fillMaxSize()) {
        if (blocked) {
            TimeBlockScreen(state, profile, unlock, nowMs(), zone)
        } else {
            holder.SaveableStateProvider("profile_screens") { content() }
        }
    }
}

private enum class BlockStep { MESSAGE, PIN, CHOOSE }

/**
 * «انتهى الوقت» or «خارج أوقات المشاهدة», full screen. «فتح بالرمز» → the parent PIN → +15 / +30 / +60
 * minutes or the rest of the day. BACK only steps back inside this screen; it never leaves it. The
 * choices close by themselves after [idleMs] without a key, and forget the PIN: a parent who walks away
 * does not leave them to the child.
 */
@Composable
fun TimeBlockScreen(
    state: SessionState,
    profile: Profile,
    unlock: TimeUnlock,
    nowMs: Long,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    initialStep: Int = 0,
    idleMs: Long = CHOICES_IDLE_MS,
) {
    var step by remember { mutableStateOf(BlockStep.entries[initialStep]) }
    // The PIN just accepted, for «تبديل الملف» (kept in memory only, never saved).
    var pin by remember { mutableStateOf<String?>(null) }
    var keys by remember { mutableIntStateOf(0) }
    fun backToMessage() {
        step = BlockStep.MESSAGE
        pin = null
    }
    // Always enabled: BACK must not reach anything behind this screen.
    BackHandler(enabled = true) { if (step != BlockStep.MESSAGE) backToMessage() }
    LaunchedEffect(step, keys) {
        if (step == BlockStep.CHOOSE) {
            delay(idleMs)
            backToMessage()
        }
    }

    val night = state is SessionState.OutsideSchedule
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                val glow = if (night) NightColors.last() else TimeColors.last()
                drawRect(Brush.verticalGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent), endY = size.height * 0.8f))
                drawRect(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.width * 0.38f,
                    ),
                )
            }
            .padding(ShashaDimens.ScreenPadding)
            .graphicsLayer { alpha = appear.value }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown) keys++
                false
            }
            .testTag(if (night) "outside_schedule_screen" else "time_up_screen"),
    ) {
        // Whose screen it is. Not over the PIN pad, which needs the height and names the profile itself.
        AnimatedVisibility(step != BlockStep.PIN, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            ProfileChip(profile)
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
            modifier = Modifier.align(Alignment.Center),
            label = "blockStep",
        ) { shown ->
            when (shown) {
                BlockStep.MESSAGE -> BlockMessage(state, profile, nowMs, zone, onUnlock = { step = BlockStep.PIN })
                BlockStep.PIN -> PinPanel(compact = true) {
                    ParentPinPad(
                        title = stringResource(R.string.pin_parent_title),
                        subtitle = stringResource(R.string.time_unlock_pin_body, profile.name),
                        check = unlock::check,
                        onAccepted = {
                            pin = it
                            step = BlockStep.CHOOSE
                        },
                        initialLockMillis = unlock::lockMillis,
                    )
                }
                BlockStep.CHOOSE -> UnlockChoices(unlock, pin, onCancel = ::backToMessage)
            }
        }
    }
}

@Composable
private fun BlockMessage(state: SessionState, profile: Profile, nowMs: Long, zone: ZoneId, onUnlock: () -> Unit) {
    val texts = rememberTimeTexts()
    val night = state is SessionState.OutsideSchedule
    val open = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { open.requestFocus() }
    }
    val reopens = when (state) {
        is SessionState.OutsideSchedule -> stringResource(R.string.outside_schedule_reopens, texts.whenNext(state.nextAllowedAtEpochMs, nowMs, zone))
        else -> {
            val start = profile.schedule?.takeUnless { it.isAlwaysAllowed }?.allowedFromMinute
            if (start != null) stringResource(R.string.time_up_reopens_at, texts.time(start)) else stringResource(R.string.time_up_reopens)
        }
    }
    Column(Modifier.widthIn(max = 700.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(
            glyph = if (night) MarkGlyph.MOON else MarkGlyph.HOURGLASS,
            screen = if (night) NightColors else TimeColors,
            dots = if (night) Color(0xFFFFE8A3) else Color(0xFFFFB547),
            modifier = Modifier.size(width = 118.dp, height = 126.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(if (night) R.string.outside_schedule_title else R.string.time_up_title),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("block_title"),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(
                when {
                    night && profile.isChild -> R.string.outside_schedule_body_child
                    night -> R.string.outside_schedule_body
                    profile.isChild -> R.string.time_up_body_child
                    else -> R.string.time_up_body
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        val shape = RoundedCornerShape(percent = 50)
        Row(
            Modifier
                .background(ShashaColors.Surface.copy(alpha = 0.9f), shape)
                .border(1.dp, ShashaColors.Outline, shape)
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (night) ShashaIcons.Bedtime else ShashaIcons.WatchLater, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(reopens, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("block_reopens"))
        }
        Spacer(Modifier.height(26.dp))
        ShashaButton(
            stringResource(R.string.time_unlock_with_pin),
            onUnlock,
            icon = ShashaIcons.Lock,
            modifier = Modifier.focusRequester(open).testTag("block_unlock"),
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.time_unlock_phone_hint), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
    }
}

@Composable
private fun UnlockChoices(unlock: TimeUnlock, pin: String?, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun work(action: suspend () -> Boolean) {
        if (working) return
        working = true
        failed = false
        scope.launch {
            try {
                failed = !action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                working = false
            }
        }
    }

    // Once the grant is stored the session opens by itself and this screen goes away.
    fun pick(choice: UnlockChoice) = work {
        unlock.grant(choice)
        true
    }
    Column(Modifier.widthIn(max = 760.dp).testTag("unlock_choices"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.time_unlock_choose_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.time_unlock_choose_body),
            style = MaterialTheme.typography.bodyLarge,
            color = ShashaColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
        Row(Modifier.focusRestorer(first).focusGroup(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            UnlockChoice.entries.forEachIndexed { index, choice ->
                ChoiceTile(
                    choice = choice,
                    onClick = { pick(choice) },
                    modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag("unlock_${choice.name}"),
                )
            }
        }
        Spacer(Modifier.height(26.dp))
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            when {
                working -> ShashaLoader(size = 36.dp)
                failed -> Text(stringResource(R.string.time_unlock_failed), style = MaterialTheme.typography.titleSmall, color = ShashaColors.Error)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShashaButton(
                stringResource(R.string.time_unlock_switch_profile),
                { pin?.let { work { unlock.switchProfile(it) == PinCheck.Accepted } } },
                icon = ShashaIcons.SwapProfiles,
                modifier = Modifier.testTag("unlock_switch_profile"),
            )
            ShashaButton(stringResource(R.string.action_cancel), onCancel, icon = ShashaIcons.Close, modifier = Modifier.testTag("unlock_cancel"))
        }
    }
}

@Composable
private fun ChoiceTile(choice: UnlockChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(width = 156.dp, height = 132.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = accent.main,
        focusedContentColor = accent.onMain,
        scale = 1.08f,
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            val minutes = choice.minutes
            if (minutes != null) {
                // "+15" reads left to right, also inside an Arabic line.
                Text("+$minutes", style = MaterialTheme.typography.displaySmall.copy(textDirection = TextDirection.Ltr))
                Text(stringResource(R.string.unit_minutes), style = MaterialTheme.typography.titleSmall)
            } else {
                Icon(ShashaIcons.WatchLater, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.time_unlock_rest_of_today), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** How long the choices after the PIN wait for a key (like the profile picker after a child's «خروج»). */
const val CHOICES_IDLE_MS = 60_000L

/** Whose screen this is, quietly in the corner: the avatar and the name. */
@Composable
fun ProfileChip(profile: Profile, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .background(ShashaColors.Surface.copy(alpha = 0.8f), shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)
            .testTag("profile_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile.avatar, profile.name, 36.dp)
        Spacer(Modifier.width(10.dp))
        Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

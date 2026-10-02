package com.ahmadnayfeh.shasha.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.remote.RemoteService
import com.ahmadnayfeh.shasha.search.VoiceAvailability
import com.ahmadnayfeh.shasha.search.VoiceSearch
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinDialog
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.navigation.SettingsActions
import com.ahmadnayfeh.shasha.ui.parental.LocalParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.screens.guide.ExplainerVideo
import com.ahmadnayfeh.shasha.ui.screens.settings.rememberProtectionStatus
import com.ahmadnayfeh.shasha.ui.theme.LocalAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The settings tab of the adult profile ([SettingsHubScreen]). The parental parts ask for the parent PIN first
 * (remembered a few minutes by the profile's one [ParentUnlock], shared with the card menus): the panes of
 * [SettingsCategory.parental], the pairing screen, the backup. «تغيير رمز الوالدين» asks for the current PIN itself.
 */
@Composable
fun SettingsRoute(focus: ContentFocus, settings: SettingsActions) {
    val context = LocalContext.current
    val container = context.appContainer
    val unlock = LocalParentUnlock.current ?: containerViewModel { ParentUnlock(it.parentPin, unlockMs = { it.behavior.values.value.parentUnlockMinutes * 60_000L }) }
    val vm = containerViewModel { c ->
        SettingsHubViewModel(
            profileRepository = c.profiles,
            subscriptions = c.subscriptions,
            sponsorBlock = c.sponsorBlockSettings,
            playback = c.playbackSettings,
            remoteSettings = c.remoteSettings,
            remoteState = c.remote.state,
            feed = c.feed,
            usageRepository = c.usage,
            engine = c.policyEngine,
            zone = { c.clock.zone },
            appScope = c.appScope,
            behaviorSettings = c.behavior,
            restartRemote = { c.remote.restart() },
            phonePaired = { c.phoneReach.isPaired() },
        )
    }
    var selected by rememberSaveable { mutableStateOf(SettingsCategory.PROFILES) }
    val gate = remember(unlock) { ParentGate(unlock) }
    // The PIN's few minutes run out while the settings are shown: the parental panes close again.
    LaunchedEffect(gate, gate.unlocked) {
        if (gate.unlocked) {
            while (unlock.isUnlocked) delay(UNLOCK_CHECK_MS)
            gate.unlocked = false
        }
    }
    LaunchedEffect(selected, gate.unlocked) {
        if (selected == SettingsCategory.STATS && gate.unlocked) vm.loadUsage()
    }

    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val subscriptionCount by vm.subscriptionCount.collectAsStateWithLifecycle()
    val sponsorCategories by vm.sponsorCategories.collectAsStateWithLifecycle()
    val maxQuality by vm.maxQuality.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val syncHealth by vm.syncHealth.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val behavior by vm.behavior.collectAsStateWithLifecycle()
    val remotePort by vm.remotePort.collectAsStateWithLifecycle()
    val messages = LocalAppMessages.current
    val resetDone = stringResource(R.string.reset_done)
    val escapeNeedsPhone = stringResource(R.string.serious_escape_needs_phone)
    // Read every 2 s while shown (the list's dot, the pane); starts the guard when it can run, as «حالة الحماية» does.
    val protection by rememberProtectionStatus()
    val returns by container.guardReturnTimes.times.collectAsStateWithLifecycle(initialValue = emptyList())
    val versions = remember { AppVersions(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, container.videoSource.extractorVersion) }
    val device = remember { DeviceFacts.current() }
    // Which voice recognizers the TV offers (package queries: asked off the main thread, shown once known).
    val voice by produceState<VoiceAvailability?>(null, context) { value = withContext(Dispatchers.IO) { VoiceSearch.availability(context) } }
    val now = remember(syncHealth) { container.clock.millis() }

    val actions = remember(settings, gate, vm) {
        SettingsHubActions(
            unlock = { gate.guarded {} },
            openProfiles = { gate.guarded(settings.openProfiles) },
            openProfile = { id -> gate.guarded { settings.openProfile(id) } },
            openChangePin = settings.openChangePin,
            openBlocklist = { gate.guarded(settings.openBlocklist) },
            openAllowlist = { id -> gate.guarded { settings.openAllowlist(id) } },
            openStats = { gate.guarded(settings.openStats) },
            openProtection = settings.openProtection,
            openRemote = { gate.guarded(settings.openRemote) },
            openBackup = { gate.guarded(settings.openBackup) },
            openImport = settings.openImport,
            openUpdates = settings.openUpdates,
            openDiagnostics = settings.openDiagnostics,
            setQuota = { id, minutes -> gate.guarded { vm.setQuota(id, minutes) } },
            setSchedule = { id, schedule -> gate.guarded { vm.setSchedule(id, schedule) } },
            setSponsorCategory = vm::setSponsorCategory,
            resetSponsorCategories = vm::resetSponsorCategories,
            setMaxQuality = vm::setMaxQuality,
            setRemoteEnabled = { on -> gate.guarded { vm.setRemoteEnabled(on) { RemoteService.startIfWanted(context) } } },
            behavior = BehaviorActions(
                setSeriousModeChildren = { on -> gate.guarded { vm.setSeriousModeChildren(on) } },
                setSeriousModeAdult = { on -> gate.guarded { vm.setSeriousModeAdult(on) } },
                setAdultEscape = { on -> gate.guarded { vm.setAdultEscape(on) { messages?.show(escapeNeedsPhone, ShashaIcons.Info) } } },
                setAdultEntryPin = { on -> gate.guarded { vm.setAdultEntryPin(on) } },
                setVoiceLanguage = { tag -> gate.guarded { vm.setVoiceLanguage(tag) } },
                setNumber = { key, value -> gate.guarded { vm.setNumber(key, value) } },
                setPhraseMode = { mode -> gate.guarded { vm.setPhraseMode(mode) } },
                addPhrase = { phrase -> gate.guarded { vm.addPhrase(phrase) } },
                removePhrase = { phrase -> gate.guarded { vm.removePhrase(phrase) } },
                setCustomPhrase = { phrase -> gate.guarded { vm.setCustomPhrase(phrase) } },
                setExtraMinutes = { minutes -> gate.guarded { vm.setExtraMinutes(minutes) } },
                setDefaultSpeed = { speed -> gate.guarded { vm.setDefaultSpeed(speed) } },
                reset = { keys -> gate.guarded { vm.resetBehavior(keys) } },
                setPort = { port -> gate.guarded { vm.setPort(port) } },
                resetPort = { gate.guarded { vm.resetPort() } },
                resetEverything = { gate.guarded { vm.resetEverything { messages?.show(resetDone, ShashaIcons.Refresh) } } },
            ),
            setAppLanguage = vm::setAppLanguage,
            setAppTheme = vm::setAppTheme,
            openGuide = settings.openGuide,
            openExplainer = settings.openExplainer,
        )
    }
    val language = LocalAppLanguage.current
    val explainerAvailable = remember(language) { ExplainerVideo.isAvailable(context, language) }

    SettingsHubScreen(
        state = SettingsHubState(
            versions = versions,
            unlocked = gate.unlocked,
            profiles = profiles,
            usage = usage,
            subscriptionCount = subscriptionCount,
            sponsorCategories = sponsorCategories,
            maxQuality = maxQuality,
            protection = protection,
            lastReturn = returns.firstOrNull(),
            remote = remote,
            syncHealth = syncHealth,
            device = device,
            voice = voice,
            nowMs = now,
            behavior = behavior,
            remotePort = remotePort,
            explainerAvailable = explainerAvailable,
        ),
        selected = selected,
        onSelect = { selected = it },
        actions = actions,
        focus = focus,
    )

    if (gate.asking) {
        PinDialog(onDismiss = gate::dismissed) {
            ParentPinPad(
                title = stringResource(R.string.pin_parent_title),
                subtitle = stringResource(R.string.settings_pin_body),
                check = unlock::check,
                onAccepted = { gate.accepted() },
                initialLockMillis = unlock::lockMillis,
            )
        }
    }
}

/**
 * The parent PIN in front of the parental parts: [guarded] runs an action at once while the PIN given a few
 * minutes ago still holds, otherwise after the PIN pad ([asking]) accepts it.
 */
@Stable
private class ParentGate(private val unlock: ParentUnlock) {
    var unlocked by mutableStateOf(unlock.isUnlocked)

    var asking by mutableStateOf(false)
        private set

    private var pending: (() -> Unit)? = null

    fun guarded(action: () -> Unit) {
        if (unlock.isUnlocked) {
            unlocked = true
            action()
        } else {
            pending = action
            asking = true
        }
    }

    fun accepted() {
        asking = false
        unlocked = true
        val action = pending
        pending = null
        action?.invoke()
    }

    fun dismissed() {
        asking = false
        pending = null
    }
}

private const val UNLOCK_CHECK_MS = 1_000L

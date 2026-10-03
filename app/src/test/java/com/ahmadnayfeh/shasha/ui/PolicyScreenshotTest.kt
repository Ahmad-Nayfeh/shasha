package com.ahmadnayfeh.shasha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Icon
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.PromoEnglish
import com.ahmadnayfeh.shasha.PromoEnglish.en
import com.ahmadnayfeh.shasha.PromoEnglish.pick
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ChannelUsage
import com.ahmadnayfeh.shasha.policy.DayUsage
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.RIYADH
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.policy.epochDayOf
import com.ahmadnayfeh.shasha.policy.epochMsAt
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.app.PickerMode
import com.ahmadnayfeh.shasha.ui.app.TimeBlockScreen
import com.ahmadnayfeh.shasha.ui.app.TimeUnlock
import com.ahmadnayfeh.shasha.ui.app.UnlockChoice
import com.ahmadnayfeh.shasha.ui.app.WarningBanner
import com.ahmadnayfeh.shasha.ui.components.CardMenuPanel
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.MenuItem
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinPad
import com.ahmadnayfeh.shasha.ui.components.PinPadState
import com.ahmadnayfeh.shasha.ui.components.PinPanel
import com.ahmadnayfeh.shasha.ui.components.PolicyNotice
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.rememberTimeTexts
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.RailAction
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.screens.home.HomeScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeState
import com.ahmadnayfeh.shasha.ui.screens.home.HomeSync
import com.ahmadnayfeh.shasha.ui.screens.kids.AllowedChannelsScreen
import com.ahmadnayfeh.shasha.ui.screens.kids.AllowedState
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsHomeScreen
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsHomeState
import com.ahmadnayfeh.shasha.ui.screens.profiles.ProfilePickerScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.AllowlistScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.BlocklistScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.ProfileEditScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.ProfilesScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.SchedulePanel
import com.ahmadnayfeh.shasha.ui.screens.settings.StatsScreen
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupScreen
import com.ahmadnayfeh.shasha.ui.screens.setup.SetupStep
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the profiles and parental controls (phase 3), with fake data and a fixed clock, at
 * 1920x1080. Record: ./gradlew :app:recordRoborazziDebug; CI verifies them (:app:verifyRoborazziDebug).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class PolicyScreenshotTest {
    @get:Rule
    val compose = PromoEnglish.composeRule()

    @OptIn(ExperimentalCoilApi::class)
    private fun snap(name: String, keys: List<Key> = emptyList(), content: @Composable () -> Unit) {
        PromoEnglish.assumeRecorded(name)
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme(language = PromoEnglish.language()) { content() }
            }
        }
        compose.waitForIdle()
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        PromoEnglish.capture(compose, name)
    }

    // ---- Data ------------------------------------------------------------------------------------------

    private val adult = Profile.newAdult(en("أحمد"), id = "owner")
    private val sara = Profile.newChild(en("سارة"), id = "sara", avatar = "rocket").copy(
        dailyQuotaMinutes = 60,
        schedule = Schedule(7 * 60, 20 * 60),
        allowlist = Allowlist(
            channels = listOf(
                ListedChannel("UCkids00000000000000000a1", en("عالم الطبيعة")),
                ListedChannel("UCkids00000000000000000a2", "Space Lab"),
                ListedChannel("UCkids00000000000000000a3", en("حكايات قبل النوم")),
                ListedChannel("UCkids00000000000000000a4", "Math Corner"),
            ),
            playlists = listOf(
                ListedPlaylist("PLkids000000000000000001", en("تعلم الحروف مع أرنوب")),
                ListedPlaylist("PLkids000000000000000002", en("تجارب علمية للصغار")),
            ),
        ),
    )
    private val omar = Profile.newChild(en("عمر"), id = "omar", avatar = "bear").copy(dailyQuotaMinutes = 45, hideShorts = true)
    private val profiles = listOf(adult, sara, omar)

    private val kidsVideos: List<VideoUi> = listOf(
        UiFixtures.video(1, "رحلة إلى أعماق المحيط: كائنات لم ترها من قبل", "عالم الطبيعة", 754, 2 * HOUR),
        UiFixtures.video(2, "How Rockets Actually Work — A Visual Guide", "Space Lab", 1082, 5 * HOUR),
        UiFixtures.video(3, "حكاية الأرنب الشجاع", "حكايات قبل النوم", 540, DAY + 3 * HOUR),
        UiFixtures.video(4, "لماذا تتغير أشكال القمر؟", "عالم الطبيعة", 402, 2 * DAY),
        UiFixtures.video(5, "Counting Shapes: A Game for Little Mathematicians", "Math Corner", 615, 3 * DAY),
        UiFixtures.video(6, "الدب القطبي وصغاره في الثلج", "عالم الطبيعة", 930, 4 * DAY),
        UiFixtures.video(7, "حكاية النجمة التي أرادت أن تنام", "حكايات قبل النوم", 480, 6 * DAY),
        UiFixtures.video(8, "Planets Song for Kids", "Space Lab", 212, 9 * DAY),
    ).map { VideoUi(it, it.publishedAtEpochMs) }

    private val kidsPlaylists = listOf(
        PlaylistUi(PlaylistItem("PLkids000000000000000001", en("تعلم الحروف مع أرنوب"), en("حكايات قبل النوم"), "fake://playlist/letters", 28)),
        PlaylistUi(PlaylistItem("PLkids000000000000000002", en("تجارب علمية للصغار"), "Space Lab", "fake://playlist/science", 12)),
    )

    private val kidsChannels = listOf(
        ChannelItem("UCkids00000000000000000a1", en("عالم الطبيعة"), "fake://avatar/1", null),
        ChannelItem("UCkids00000000000000000a2", "Space Lab", "fake://avatar/22", null),
        ChannelItem("UCkids00000000000000000a3", en("حكايات قبل النوم"), null, null),
        ChannelItem("UCkids00000000000000000a4", "Math Corner", "fake://avatar/4444", null),
    ).map { ChannelUi(it) }

    private val today = epochDayOf("2026-09-28")

    private val saraWeek = UsageSummary(
        profileId = sara.id,
        days = listOf(38, 62, 45, 20, 58, 71, 41).mapIndexed { i, minutes -> DayUsage(today - 6 + i, minutes * 60L) },
        thisWeekSeconds = 335 * 60L,
        previousWeekSeconds = 290 * 60L,
        changePercent = 16,
        topChannels = listOf(
            ChannelUsage("UCkids00000000000000000a1", en("عالم الطبيعة"), 118 * 60L),
            ChannelUsage("UCkids00000000000000000a3", en("حكايات قبل النوم"), 74 * 60L),
            ChannelUsage("UCkids00000000000000000a2", "Space Lab", 52 * 60L),
            ChannelUsage("UCkids00000000000000000a4", "Math Corner", 21 * 60L),
        ),
        surrendersLast7Days = 0,
    )

    private val adultWeek = UsageSummary(
        profileId = adult.id,
        days = listOf(95, 40, 130, 0, 75, 160, 55).mapIndexed { i, minutes -> DayUsage(today - 6 + i, minutes * 60L) },
        thisWeekSeconds = 555 * 60L,
        previousWeekSeconds = 710 * 60L,
        changePercent = -22,
        topChannels = listOf(
            ChannelUsage("UCfixturecalligraphy0001", en("فنون الخط"), 140 * 60L),
            ChannelUsage("UCfixturesub0000000000a4", "Math Corner", 95 * 60L),
            ChannelUsage("UCfixturesub0000000000a7", en("رياضة اليوم"), 64 * 60L),
            ChannelUsage("UCfixturesub0000000000a3", en("مطبخ سلمى"), 38 * 60L),
            ChannelUsage("UCfixturesub0000000000a8", en("رحّالة"), 12 * 60L),
        ),
        surrendersLast7Days = 3,
    )

    private val unlock = object : TimeUnlock {
        override suspend fun check(pin: String): PinCheck = PinCheck.Accepted

        override suspend fun lockMillis(): Long = 0

        override suspend fun grant(choice: UnlockChoice) = Unit

        override suspend fun switchProfile(pin: String): PinCheck = PinCheck.Accepted
    }

    // ---- Picker and PIN --------------------------------------------------------------------------------

    @Test
    fun profilePicker() = snap("profile_picker") {
        ProfilePickerScreen(profiles, currentId = adult.id, locked = { it.isAdult }, mode = PickerMode.START, onChoose = {})
    }

    @Test
    fun pinPad() = snap("pin_pad") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProfilePickerScreen(profiles, currentId = adult.id, locked = { it.isAdult }, mode = PickerMode.START, onChoose = {})
            // What the dialog window's dim looks like.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            val state = remember { PinPadState().apply { type('2'); type('4') } }
            PinPanel {
                PinPad(
                    state = state,
                    title = pick("رمز الوالدين", "Parent PIN"),
                    subtitle = pick("للدخول إلى ملف «⁨أحمد⁩»", "To enter \u2068${adult.name}\u2069's profile"),
                    notice = pick("رمز غير صحيح. بقيت 4 محاولات.", "Wrong PIN. 4 attempts left."),
                    onSubmit = {},
                    header = { ProfileAvatar(adult.avatar, adult.name, 64.dp) },
                )
            }
        }
    }

    // ---- The child's profile ---------------------------------------------------------------------------

    @Composable
    private fun KidsShell(tab: Tab, content: @Composable () -> Unit) {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        val exit = remember { listOf(RailAction(R.string.rail_exit, "rail_exit", {}) { Icon(ShashaIcons.Exit, contentDescription = null, modifier = it) }) }
        KidsAccent { MainShell(tab, {}, itemFocus, tabs = Tab.Kids, actions = exit) { content() } }
    }

    @Test
    fun kidsHome() = snap("kids_home") {
        KidsShell(Tab.HOME) {
            KidsHomeScreen(
                state = KidsHomeState(loaded = true, name = sara.name, avatar = sara.avatar, videos = kidsVideos, playlists = kidsPlaylists, channelCount = 4),
                watched = remember { mutableStateOf(mapOf(kidsVideos[2].video.videoId to 0.4f, kidsVideos[5].video.videoId to 1f)) },
                nowMs = NOW,
                focus = remember { ContentFocus() },
                extractorVersion = "v0.26.5",
                onPlay = {},
                onOpenPlaylist = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun kidsHomeEmpty() = snap("kids_home_empty") {
        KidsShell(Tab.HOME) {
            KidsHomeScreen(
                state = KidsHomeState(loaded = true, name = omar.name, avatar = omar.avatar),
                watched = remember { mutableStateOf(emptyMap<String, Float>()) },
                nowMs = NOW,
                focus = remember { ContentFocus() },
                extractorVersion = "v0.26.5",
                onPlay = {},
                onOpenPlaylist = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun kidsRail() = snap("kids_rail") {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            itemFocus.getValue(Tab.HOME).requestFocus()
        }
        val exit = remember { listOf(RailAction(R.string.rail_exit, "rail_exit", {}) { Icon(ShashaIcons.Exit, contentDescription = null, modifier = it) }) }
        KidsAccent {
            MainShell(Tab.HOME, {}, itemFocus, tabs = Tab.Kids, actions = exit) {
                KidsHomeScreen(
                    state = KidsHomeState(loaded = true, name = sara.name, avatar = sara.avatar, videos = kidsVideos, playlists = kidsPlaylists, channelCount = 4),
                    watched = remember { mutableStateOf(emptyMap<String, Float>()) },
                    nowMs = NOW,
                    focus = remember { ContentFocus(pending = false) },
                    extractorVersion = "v0.26.5",
                    onPlay = {},
                    onOpenPlaylist = {},
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun kidsAllowedChannels() = snap("kids_allowed_channels") {
        KidsShell(Tab.ALLOWED_CHANNELS) {
            AllowedChannelsScreen(AllowedState(loaded = true, channels = kidsChannels, playlists = kidsPlaylists), remember { ContentFocus() }, { _, _ -> }, {})
        }
    }

    @Test
    fun kidsDenied() = snap("kids_denied") {
        KidsAccent { PolicyNotice(DenyReason.NOT_IN_ALLOWLIST, onBack = {}) }
    }

    // ---- Time ------------------------------------------------------------------------------------------

    @Test
    fun timeUp() = snap("time_up") {
        KidsAccent { TimeBlockScreen(SessionState.TimeUp, sara, unlock, nowMs = epochMsAt("2026-09-28T17:40"), zone = RIYADH) }
    }

    @Test
    fun timeUpUnlockChoices() = snap("time_up_unlock") {
        KidsAccent { TimeBlockScreen(SessionState.TimeUp, sara, unlock, nowMs = epochMsAt("2026-09-28T17:40"), zone = RIYADH, initialStep = 2) }
    }

    @Test
    fun outsideSchedule() = snap("outside_schedule") {
        KidsAccent {
            TimeBlockScreen(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")), sara, unlock, nowMs = epochMsAt("2026-09-28T20:30"), zone = RIYADH)
        }
    }

    @Test
    fun warningBanner() = snap("time_warning") {
        Box(Modifier.fillMaxSize()) {
            Image(Fixtures.fakePicture(1920, 1080).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            KidsAccent { WarningBanner(emptyFlow(), SessionState.Warning(remainingSeconds = 300), showMs = NEVER) }
        }
    }

    // ---- Settings --------------------------------------------------------------------------------------

    @Test
    fun adultRail() = snap("rail_adult_profile") {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            itemFocus.getValue(Tab.HOME).requestFocus()
        }
        val switch = remember { listOf(RailAction(R.string.rail_switch_profile, "rail_switch_profile", {}) { ProfileAvatar(adult.avatar, adult.name, 28.dp) }) }
        MainShell(Tab.HOME, {}, itemFocus, actions = switch) {
            HomeScreen(
                HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20),
                remember { mutableStateOf(emptyMap<String, Float>()) },
                HomeSync(),
                NOW,
                remember { ContentFocus(pending = false) },
                "v0.26.5",
                onRefresh = {},
                onPlay = {},
                onImport = {},
                onSearch = {},
                onCheckUpdates = {},
            )
        }
    }

    @Test
    fun stats() = snap("stats") {
        StatsScreen(profiles, selected = sara, summary = saraWeek, today = today, onSelect = {}, animate = false)
    }

    @Test
    fun statsAdult() = snap("stats_adult") {
        StatsScreen(profiles, selected = adult, summary = adultWeek, today = today, onSelect = {}, animate = false)
    }

    @Test
    fun profilesList() = snap("settings_profiles") {
        ProfilesScreen(profiles, remember { ContentFocus() }, onOpenProfile = {}, onAddChild = {})
    }

    @Test
    fun profileEdit() = snap("profile_edit") {
        KidsAccent { ProfileEditScreen(sara, canDelete = true, onUpdate = {}, onOpenAllowlist = {}, onDelete = {}) }
    }

    @Test
    fun profileSchedule() = snap("profile_schedule") {
        KidsAccent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ProfileEditScreen(sara, canDelete = true, onUpdate = {}, onOpenAllowlist = {}, onDelete = {})
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                SchedulePanel("أوقات المشاهدة", sara.schedule, rememberTimeTexts(), onSave = {})
            }
        }
    }

    /** A household with five children: the rows scroll inside the card, each asking for the PIN first. */
    @Test
    fun cardMenuParental() = snap("card_menu_parental") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            HomeScreen(
                HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20),
                remember { mutableStateOf(emptyMap<String, Float>()) },
                HomeSync(),
                NOW,
                remember { ContentFocus(pending = false) },
                "v0.26.5",
                onRefresh = {},
                onPlay = {},
                onImport = {},
                onSearch = {},
                onCheckUpdates = {},
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            val children = listOf("سارة", "عمر", "ليان", "يوسف", "عبد الرحمن الصغير")
            val rows = children.mapIndexed { i, name ->
                MenuItem(ShashaIcons.Shield, "أضف إلى قائمة سماح ⁨$name⁩", "menu_allow_$i", locked = true) {}
            } + MenuItem(ShashaIcons.Block, "حظر القناة", "menu_block", locked = true) {}
            CardMenuPanel("Space Lab", "قناة", rows)
        }
    }

    @Test
    fun allowlist() = snap("allowlist") {
        AllowlistScreen(sara, onRemove = { _, _ -> }, onAddFromSubscriptions = {})
    }

    @Test
    fun blocklist() = snap("blocklist") {
        BlocklistScreen(
            Blocklist(
                channels = listOf(ListedChannel("UCblocked0000000000000001", "مقالب مخيفة"), ListedChannel("UCblocked0000000000000002", "Prank Masters")),
                titleKeywords = listOf("رعب", "مقلب", "horror"),
            ),
            onUnblock = {},
            onAddKeyword = {},
            onRemoveKeyword = {},
        )
    }

    // ---- First start -----------------------------------------------------------------------------------

    @Test
    fun setupWelcome() = snap("setup_welcome") {
        SetupScreen(SetupStep.WELCOME, busy = false, ownerName = "", ownerAvatar = Profile.DEFAULT_ADULT_AVATAR, onStart = {}, onPin = {}, onOwner = { _, _ -> }, onChild = { _, _ -> }, onSkipChild = {})
    }

    @Test
    fun setupPin() = snap("setup_pin") {
        SetupScreen(SetupStep.PIN, busy = false, ownerName = "", ownerAvatar = Profile.DEFAULT_ADULT_AVATAR, onStart = {}, onPin = {}, onOwner = { _, _ -> }, onChild = { _, _ -> }, onSkipChild = {})
    }

    @Test
    fun setupChild() = snap("setup_child") {
        // In English the text is a line longer and the buttons are squeezed out of the 1080p TV: at a smaller text size it fits.
        PromoEnglish.smallerText(0.85f) {
            SetupScreen(SetupStep.CHILD, busy = false, ownerName = adult.name, ownerAvatar = adult.avatar, onStart = {}, onPin = {}, onOwner = { _, _ -> }, onChild = { _, _ -> }, onSkipChild = {})
        }
    }

    private companion object {
        const val NEVER = 3_600_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}

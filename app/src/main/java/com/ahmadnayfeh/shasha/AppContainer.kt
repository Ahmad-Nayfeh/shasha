package com.ahmadnayfeh.shasha

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.ahmadnayfeh.shasha.backup.BackupManager
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.AppearanceState
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.HomeSuggestions
import com.ahmadnayfeh.shasha.data.NetworkStatus
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.PolicyStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.feed.FeedSyncScheduler
import com.ahmadnayfeh.shasha.lock.AppVisibility
import com.ahmadnayfeh.shasha.lock.GuardReturnTimes
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.player.PlayerHub
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.DeviceClock
import com.ahmadnayfeh.shasha.policy.DeviceUptime
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.UsageTracker
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.spendingTime
import com.ahmadnayfeh.shasha.remote.NsdAdvertiser
import com.ahmadnayfeh.shasha.remote.NsdAnnouncer
import com.ahmadnayfeh.shasha.remote.PhoneConfirmations
import com.ahmadnayfeh.shasha.remote.PhoneParental
import com.ahmadnayfeh.shasha.remote.PhonePin
import com.ahmadnayfeh.shasha.remote.PhonePlayRequests
import com.ahmadnayfeh.shasha.remote.PhoneReach
import com.ahmadnayfeh.shasha.remote.PhoneSettings
import com.ahmadnayfeh.shasha.remote.PhoneProfileChoices
import com.ahmadnayfeh.shasha.remote.RemoteRevision
import com.ahmadnayfeh.shasha.remote.RemoteRuntime
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.remote.RemoteTexts
import com.ahmadnayfeh.shasha.remote.TvDisplay
import com.ahmadnayfeh.shasha.remote.TvPhoneReach
import com.ahmadnayfeh.shasha.remote.TvRemoteController
import com.ahmadnayfeh.shasha.search.SearchHistory
import com.ahmadnayfeh.shasha.search.VoiceSearchRequests
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockClient
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockRepository
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.ahmadnayfeh.shasha.ui.navigation.NavigationRequests
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsFeed
import com.ahmadnayfeh.shasha.ui.theme.AppLocale
import com.ahmadnayfeh.shasha.update.ApkInspector
import com.ahmadnayfeh.shasha.update.GitHubReleases
import com.ahmadnayfeh.shasha.update.InstallEvents
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.UpdateInstaller
import com.ahmadnayfeh.shasha.update.Updater
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTube
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: one instance per process, owned by [ShashaApp].
 * Everything is created lazily so the first frame is not delayed by extractor initialisation.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * The one HTTP client of the app (thumbnails, SponsorBlock and GitHub Releases). The YouTube module
     * reuses its connection pool but applies its own per-client headers.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
                }
            }
            .build()
    }

    val videoSource: VideoSource by lazy { YouTube.create(appContext, okHttpClient) }

    val mediaSourceFactory: YouTubeMediaSourceFactory by lazy { YouTubeMediaSourceFactory(appContext, okHttpClient) }

    val streams: StreamRepository by lazy { StreamRepository(videoSource) }

    val database: ShashaDatabase by lazy { ShashaDatabase.create(appContext) }

    val feedSyncScheduler: FeedSyncScheduler by lazy { FeedSyncScheduler(WorkManager.getInstance(appContext)) }

    /** New subscriptions (added, or imported from Takeout) get their videos right away. */
    val subscriptions: SubscriptionRepository by lazy {
        SubscriptionRepository(database, videoSource, onChannelsAdded = { feedSyncScheduler.syncNow() })
    }

    val feed: FeedRepository by lazy { FeedRepository(database, videoSource) }

    /** Progress, history and watch later of the current profile: they follow profile switches. */
    val watch: WatchRepository by lazy { WatchRepository(database, currentProfile) }

    val network: NetworkStatus by lazy { NetworkStatus(appContext) }

    /**
     * For writes that must outlive the screen that started them (the player's last saved position is
     * written while it closes). Main-immediate keeps them in the order they were made.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Hands a playlist from its page to the player (the next video plays when one ends). */
    val playQueues: PlayQueues by lazy { PlayQueues() }

    /**
     * A child's home: the uploads of allowlisted channels, which the feed (subscriptions only) may not follow.
     * Kept in memory, fetched gently (3 at a time, never while a video plays).
     */
    val kidsFeed: KidsFeed by lazy { KidsFeed(videoSource) }

    /**
     * «مقترحة لك» on the home screens: suggestions beside the profile's last videos, each video's kept a few hours in the
     * cache folder (a cache: not in the backup).
     */
    val homeSuggestions: HomeSuggestions by lazy { HomeSuggestions(videoSource, File(appContext.cacheDir, HomeSuggestions.FILE_NAME)) }

    // ---- Policy (SPEC §3): profiles, PIN, quota and schedule, usage. Every rule is the engine's. ----------

    /** The TV's clock, in the TV's current time zone. */
    val clock: Clock = DeviceClock

    private val behaviorStore: DataStore<Preferences> by lazy { BehaviorSettings.createStore(appContext) }

    /**
     * The parent's adjustable behaviour (Settings, behind the parent PIN): «الوضع الجاد», the time warnings, the
     * PIN's brake, the player's timings. Every consumer reads it when it acts, so a change applies at once.
     */
    val behavior: BehaviorSettings by lazy { BehaviorSettings(behaviorStore, appScope) }

    /** «المظهر واللغة» on screen: the activity and the services read the language before they draw anything. */
    val appearance: AppearanceState by lazy { AppearanceState(behavior, appScope) }

    val policyEngine: PolicyEngine by lazy { PolicyEngine(clock) { behavior.policyConfig() } }

    // ---- Search (v1.1): recent searches per profile, and the remote's SEARCH key. ----------------------------

    private val searchStore: DataStore<Preferences> by lazy { SearchHistory.createStore(appContext) }

    /** Each profile's recent searches (only profiles with search keep any). */
    val searchHistory: SearchHistory by lazy { SearchHistory(searchStore) }

    /** The remote's SEARCH key: the adult profile opens the search tab listening. */
    val voiceSearchRequests: VoiceSearchRequests = VoiceSearchRequests()

    private val policyStore: DataStore<Preferences> by lazy { PolicyStore.create(appContext) }

    val profiles: ProfileRepository by lazy { ProfileRepository(database) }

    /**
     * The persisted current profile. Creating it reads the stored id from disk (blocking, a few bytes):
     * ShashaApp does that off the main thread at start.
     */
    val currentProfile: ActiveProfile by lazy {
        ActiveProfile.create(
            profiles,
            policyStore,
            appScope,
            // The owner's first name is written once, in the language the app shows then.
            ownerName = AppLocale.localize(appContext, appearance.current().language).getString(R.string.profile_owner_default_name),
        )
    }

    /** Lockouts count down on the TV's uptime: setting its clock forward does not end one. */
    val parentPin: ParentPinRepository by lazy {
        ParentPinRepository(policyStore, clock, uptime = DeviceUptime.of(appContext), lockout = { behavior.pinLockout() })
    }

    val overrides: OverridesRepository by lazy { OverridesRepository(policyStore) }

    val usage: UsageRepository by lazy { UsageRepository(database) }

    /** SPEC §4's playback lock, persisted (phase 4 drives it). */
    val playbackLock: PlaybackLockStore by lazy { PlaybackLockStore(policyStore, appScope) }

    /**
     * SPEC §4's in-app lock over [playbackLock]: the player locks every video it starts, the lock screens
     * open it (the parent PIN, the adult's exit challenge), and the phone's parent section ends it
     * ([LockController.unlockByParent]).
     */
    val lock: LockController by lazy {
        LockController(
            engine = policyEngine,
            store = playbackLock,
            profile = currentProfile.profile,
            verifyPin = { pin -> policy.verifyParentPin(pin) },
            pinLockMillis = { parentPin.remainingLockMillis() },
            surrenders = { event -> usage.recordSurrender(event) },
            scope = appScope,
            seriousMode = behavior.values.map { it.seriousMode }.distinctUntilChanged(),
        )
    }

    // ---- The guard outside the app (SPEC §4): lock/GuardService. ---------------------------------------

    /** Whether Shasha's activity is in front (MainActivity reports it): the guard acts only when it is not. */
    val appVisibility: AppVisibility = AppVisibility()

    /** The guard's last returns, measured on the TV (SPEC acceptance: Home → back ≤ 2 s). */
    val guardReturnTimes: GuardReturnTimes by lazy { GuardReturnTimes.create(appContext) }

    val session: SessionController by lazy {
        SessionController(policyEngine, currentProfile.profile, usage, overrides, appScope, onNewDay = ::dailyCleanup)
    }

    val usageTracker: UsageTracker by lazy {
        val counting = combine(session.isReady, session.state) { ready, state -> ready && state.countsTowardQuota }.distinctUntilChanged()
        UsageTracker(usage, policyEngine::today, currentProfile.profile, counting, appScope)
    }

    /**
     * True while a profile's screens are on screen (the UI sets it): not the first-start setup, not the
     * profile picker. Nobody's quota is spent on those ([spendingTime]).
     */
    val profileScreensShown = MutableStateFlow(false)

    /**
     * True while the parent PIN has let the current child profile out (its «خروج»: the picker chooses who comes
     * next; RootViewModel sets it). The guard keeps a child profile inside the app, except then (owner decision).
     */
    val childLetOut = MutableStateFlow(false)

    /** The one door to every rule: lists, playback, profile switches, the parent PIN. */
    val policy: PolicyGate by lazy {
        PolicyGate(
            engine = policyEngine,
            active = currentProfile,
            profiles = profiles,
            session = session,
            locks = playbackLock,
            parentPin = parentPin,
            // The check needs the video's facts (channel, duration), whatever its quality: a "made for kids" video with
            // only a 360p file is judged like any other. The player decides whether that file may play.
            lookup = VideoLookup { videoId -> streams.stream(videoId, allowLowQuality = true).let { LookedUpVideo(it.video, it) } },
            scope = appScope,
            surrenders = { event -> usage.recordSurrender(event) },
            childLetOut = childLetOut,
        )
    }

    // ---- The phone remote (SPEC §5) and the manual backup. ---------------------------------------------------

    /**
     * The phone's page is Arabic (remote/web): its answers (labels, refusals, errors) are read in Arabic whatever language
     * the TV shows.
     */
    private val phoneResources by lazy { AppLocale.localize(appContext, AppLanguage.ARABIC).resources }

    /** The player on screen, as the phone remote sees and drives it. */
    val playerHub: PlayerHub = PlayerHub()

    /** Destinations the phone asks the current profile's screens to open. */
    val navigationRequests: NavigationRequests = NavigationRequests()

    private val remoteStore: DataStore<Preferences> by lazy { RemoteSettings.createStore(appContext) }

    /** «التحكم من الجوال»: on/off, the pairing token, the port. */
    val remoteSettings: RemoteSettings by lazy { RemoteSettings(remoteStore) }

    /** SPEC: «نسخة احتياطية JSON يدوية», from Settings → الاستيراد والتصدير and from the phone. */
    val backup: BackupManager by lazy {
        BackupManager(
            db = database,
            policyStore = policyStore,
            sections = listOf(
                RemoteSettings.backupSection(remoteStore),
                SponsorBlockSettings.backupSection(sponsorStore),
                PlaybackSettings.backupSection(playbackStore),
                BehaviorSettings.backupSection(behaviorStore),
            ),
            clock = clock,
            appVersion = BuildConfig.VERSION_NAME,
            filesDir = { appContext.getExternalFilesDir(null) },
            afterRestore = { report ->
                // Restored subscriptions get their videos; a restored PIN logs every phone out of the parent section.
                feedSyncScheduler.syncNow()
                if (report.pinChanged) remote.revokeParentSessions()
            },
        )
    }

    /**
     * The parent PIN as the phone checks it: its own attempt count, saved like the TV's (a restart grants no new
     * guesses), never the TV's (owner decision: guesses on the TV never close the parent's phone route).
     */
    val phonePin: PhonePin by lazy { PhonePin(policyStore, clock, uptime = DeviceUptime.of(appContext), lockout = { behavior.pinLockout() }) }

    /** The profile the phone chose («اختيار الملف»), after PolicyGate allowed it: the TV's picker follows it. */
    val phoneProfileChoices: PhoneProfileChoices = PhoneProfileChoices()

    /**
     * v1.2: videos that only the phone could end early wait here for the parent's «تأكيد» on the phone (the player
     * asks; the phone answers through [remote]).
     */
    val phoneConfirmations: PhoneConfirmations = PhoneConfirmations()

    /**
     * v1.2: links from the phone wait here for «قبول» on the TV (the profile's screens show the question); one allowed
     * for a profile the TV has left, or waiting when a video gets locked, is dropped (the phone reads why).
     */
    val playRequests: PhonePlayRequests by lazy {
        val texts = RemoteTexts(phoneResources)
        PhonePlayRequests(appScope, texts).also { requests ->
            appScope.launch { currentProfile.id.collect { requests.onProfile(it) } }
            appScope.launch { lock.lock.collect { if (it != null) requests.onLocked(texts.deny(DenyReason.LOCKED)) } }
        }
    }

    /** v1.2: whether a phone is paired and whether one is there now (its page polled in the last minute). */
    val phoneReach: PhoneReach by lazy { TvPhoneReach(remoteSettings, remote) }

    /**
     * What makes the phone pages read their parent section again: every setting, the profiles (rules and lists) and the
     * subscriptions, changed from any phone or from the TV itself.
     */
    private val remoteRevision: RemoteRevision by lazy {
        RemoteRevision(
            CoroutineScope(appScope.coroutineContext + Dispatchers.Default),
            listOf(
                behavior.values,
                sponsorBlockSettings.categories,
                playbackSettings.maxQuality,
                remoteSettings.port,
                profiles.observeAll(),
                // A switch changes which profile is on the TV (it cannot be deleted then).
                currentProfile.id,
                subscriptions.observe(),
            ),
        )
    }

    /**
     * The phone remote's server and its controller. [com.ahmadnayfeh.shasha.remote.RemoteService] starts and
     * stops it; creating it starts nothing.
     */
    val remote: RemoteRuntime by lazy {
        val controller = TvRemoteController(
            gate = policy,
            profiles = profiles,
            session = session,
            lock = lock,
            lists = ParentalLists(profiles),
            subscriptions = subscriptions,
            source = videoSource,
            phonePin = phonePin,
            backup = backup,
            player = playerHub,
            display = TvDisplay(appContext, appVisibility, profileScreensShown, navigationRequests),
            playQueues = playQueues,
            texts = RemoteTexts(phoneResources),
            seriousMode = { behavior.policyConfig().seriousMode },
            profileChoices = phoneProfileChoices,
            settings = PhoneSettings(
                behavior = behavior,
                sponsorBlock = sponsorBlockSettings,
                playback = playbackSettings,
                remote = remoteSettings,
                resources = phoneResources,
                scope = appScope,
                restartRemote = { remote.restart() },
                portAvailable = { port -> remote.portAvailable(port) },
            ),
            parental = PhoneParental(
                profiles = profiles,
                lists = ParentalLists(profiles),
                usage = usage,
                engine = policyEngine,
                zone = { clock.zone },
                source = videoSource,
                resources = phoneResources,
                errors = RemoteTexts(phoneResources)::sourceError,
                currentId = { currentProfile.awaitProfile().id },
            ),
            revision = { remoteRevision.value },
            movingTo = { remote.movingTo.value },
            adultEntryPin = { behavior.policyConfig().adultEntryNeedsPin },
            confirmations = phoneConfirmations,
            playRequests = playRequests,
        )
        RemoteRuntime(
            remoteSettings, controller, NsdAnnouncer(NsdAdvertiser(appContext)), phonePin = phonePin,
            pinFailures = { behavior.pinLockout().maxFailures },
        ).also { runtime ->
            // A new parent PIN logs every phone out of the parent section.
            appScope.launch { parentPin.changes.collect { runtime.revokeParentSessions() } }
        }
    }

    // ---- SponsorBlock (SPEC §1, §7). ------------------------------------------------------------------------

    private val sponsorStore: DataStore<Preferences> by lazy { SponsorBlockSettings.createStore(appContext) }

    /** «فئات SponsorBlock»: which categories every profile's videos skip. */
    val sponsorBlockSettings: SponsorBlockSettings by lazy { SponsorBlockSettings(sponsorStore) }

    /** A video's segments, asked for by the prefix of its hash and kept in Room for a day. */
    val sponsorBlock: SponsorBlockRepository by lazy {
        SponsorBlockRepository(SponsorBlockClient(okHttpClient), database.sponsorSegments(), now = clock::millis)
    }

    // ---- Updates (SPEC §6 «فحص التحديثات»). ----------------------------------------------------------------

    /** Where the system installer's answers arrive (update/InstallStatusReceiver), for the updater screen. */
    val installEvents: InstallEvents = InstallEvents()

    /**
     * The latest GitHub Release of this (private) repository, read with the build's read-only token, downloaded into
     * cacheDir/updates, checked against the installed app and handed to the system installer. Adult profile only.
     */
    val updater: Updater by lazy {
        Updater(
            releases = GitHubReleases(okHttpClient, BuildConfig.UPDATE_REPO, BuildConfig.UPDATE_TOKEN),
            inspector = ApkInspector(appContext),
            installer = UpdateInstaller(appContext),
            installed = InstalledVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
            downloads = File(appContext.cacheDir, "updates"),
            events = installEvents,
        )
    }

    // ---- Playback (SPEC §7 «الجودة الافتراضية»). -------------------------------------------------------------

    private val playbackStore: DataStore<Preferences> by lazy { PlaybackSettings.createStore(appContext) }

    /** The highest quality "Auto" reaches (1080p unless Settings changed it); the player reads it for each video. */
    val playbackSettings: PlaybackSettings by lazy { PlaybackSettings(playbackStore, appScope) }

    /** Waits (a moment at most) for the parent's settings to be read. */
    suspend fun awaitSettings() {
        withTimeoutOrNull(SETTINGS_WAIT_MS) { behavior.awaitLoaded() }
    }

    /**
     * Starts the session state and the usage tracker ([foreground]: [spendingTime], the app visible and a
     * profile's screens shown). Main thread; the current profile must have been created (see [currentProfile]).
     */
    fun startPolicy(foreground: Flow<Boolean>) {
        session.start()
        usageTracker.start(foreground)
    }

    /** Once a day (and at start): grants that ended, usage older than the stats need. */
    private suspend fun dailyCleanup(today: Long) {
        try {
            val ids = profiles.all().mapTo(HashSet()) { it.id }
            overrides.prune(today, clock.millis(), profileIds = ids.takeIf { it.isNotEmpty() })
            usage.prune(today, clock.zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Daily cleanup failed", e)
        }
    }

    private companion object {
        const val TAG = "AppContainer"
        const val SETTINGS_WAIT_MS = 3_000L
        const val USER_AGENT = "Shasha/${BuildConfig.VERSION_NAME} (Android TV)"
    }
}

package com.ahmadnayfeh.shasha

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.FeedRepository
import com.ahmadnayfeh.shasha.data.NetworkStatus
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
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
import com.ahmadnayfeh.shasha.policy.DeviceClock
import com.ahmadnayfeh.shasha.policy.DeviceUptime
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.UsageTracker
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.spendingTime
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsFeed
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
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: one instance per process, owned by [ShashaApp].
 * Everything is created lazily so the first frame is not delayed by extractor initialisation.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * The one HTTP client of the app (thumbnails, and later SponsorBlock and GitHub). The YouTube module
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

    // ---- Policy (SPEC §3): profiles, PIN, quota and schedule, usage. Every rule is the engine's. ----------

    /** The TV's clock, in the TV's current time zone. */
    val clock: Clock = DeviceClock

    val policyEngine: PolicyEngine by lazy { PolicyEngine(clock) }

    private val policyStore: DataStore<Preferences> by lazy { PolicyStore.create(appContext) }

    val profiles: ProfileRepository by lazy { ProfileRepository(database) }

    /**
     * The persisted current profile. Creating it reads the stored id from disk (blocking, a few bytes):
     * ShashaApp does that off the main thread at start.
     */
    val currentProfile: ActiveProfile by lazy {
        ActiveProfile.create(profiles, policyStore, appScope, ownerName = appContext.getString(R.string.profile_owner_default_name))
    }

    /** Lockouts count down on the TV's uptime: setting its clock forward does not end one. */
    val parentPin: ParentPinRepository by lazy { ParentPinRepository(policyStore, clock, uptime = DeviceUptime.of(appContext)) }

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

    /** The one door to every rule: lists, playback, profile switches, the parent PIN. */
    val policy: PolicyGate by lazy {
        PolicyGate(
            engine = policyEngine,
            active = currentProfile,
            profiles = profiles,
            session = session,
            locks = playbackLock,
            parentPin = parentPin,
            lookup = VideoLookup { videoId -> streams.stream(videoId).let { LookedUpVideo(it.video, it) } },
            scope = appScope,
            surrenders = { event -> usage.recordSurrender(event) },
        )
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
        const val USER_AGENT = "Shasha/${BuildConfig.VERSION_NAME} (Android TV)"
    }
}

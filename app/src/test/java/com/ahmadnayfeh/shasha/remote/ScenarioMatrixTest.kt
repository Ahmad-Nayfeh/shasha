package com.ahmadnayfeh.shasha.remote

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.backup.BackupManager
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.PolicyStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.lock.GuardDemand
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.lock.guardDemand
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.player.PlayerCommand
import com.ahmadnayfeh.shasha.player.PlayerHub
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.BLOCKED_CHANNEL
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.OTHER_PLAYLIST
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.app.PickerMode
import com.ahmadnayfeh.shasha.ui.app.RootDeps
import com.ahmadnayfeh.shasha.ui.app.RootScreen
import com.ahmadnayfeh.shasha.ui.app.RootViewModel
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The phone ↔ TV scenario matrix (docs/dev/phone-tv-scenarios.md): one case per row, run by its id.
 *
 * Every case talks HTTP to the REAL [RemoteServer], owned by the REAL [RemoteRuntime], in front of the REAL
 * [TvRemoteController] and the TV's real rules and stores, wired exactly as AppContainer wires them ([MxTv]).
 * Fakes only at the edges: YouTube ([MxSource]), the TV's screens ([MxScreens]), the player on screen
 * ([FakeActivePlayer]), the network address and NSD.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScenarioMatrixTest(private val row: MxRow) {
    @get:Rule
    val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun scenario() {
        // HttpURLConnection silently re-sends a POST whose connection broke: a write must never go twice here.
        System.setProperty("sun.net.http.retryPost", "false")
        Dispatchers.setMain(Dispatchers.Unconfined)
        val results = File(System.getProperty("user.dir"), "build/scenario-matrix").apply { mkdirs() }
        val tv = MxTv(tmp.root)
        try {
            runBlocking { withTimeout(row.timeoutMs) { row.body(tv) } }
            File(results, "${row.id}.result").writeText("PASS\t${row.title}\n")
        } catch (e: Throwable) {
            File(results, "${row.id}.result").writeText("FAIL\t${row.title}\n${e.toString().take(2_000)}\n")
            throw e
        } finally {
            tv.close()
            Dispatchers.resetMain()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = MxRows.all().map { arrayOf<Any>(it) }
    }
}

/** One row of the matrix: [id] as in the document. */
class MxRow(val id: String, val title: String, val timeoutMs: Long = 60_000, val body: suspend MxTv.() -> Unit) {
    // The test's name: ASCII only (Gradle names report files after it, in the file system's encoding).
    override fun toString() = "$id " + title.map { if (it.code in 32..126) it else '_' }.joinToString("")
}

private fun row(id: String, title: String, timeoutMs: Long = 60_000, body: suspend MxTv.() -> Unit) = MxRow(id, title, timeoutMs, body)

// ---- Fixtures --------------------------------------------------------------------------------------------------------

private const val MX_PIN = "1357"
private const val MX_WRONG = "2468"
private const val INSIDE = "inside00001"
private const val INSIDE2 = "inside00002"
private const val OUTSIDE = "outside0001"
private const val SHORT_INSIDE = "short000001"
private const val KEYWORD_INSIDE = "keyword0001"
private const val BLOCKED_VIDEO = "blocked0001"
private const val LIVE = "livevideo01"
private const val REMOVED = "removed0001"
private const val PL_VIDEO_1 = "plvideo0001"
private const val PL_VIDEO_2 = "plvideo0002"
private const val SHORTS_PLAYLIST = "PLshortsshortsshorts0000"
private const val NEW_CHANNEL = "UCnewnewnewnewnewnewnew0"
private const val NEW_PLAYLIST = "PLnewnewnewnewnewnewnew0"
private const val KEYWORD = "رعب"

private fun watch(id: String, list: String? = null, t: Long? = null) =
    "https://www.youtube.com/watch?v=$id" + (list?.let { "&list=$it" } ?: "") + (t?.let { "&t=$it" } ?: "")

private fun channelLink(id: String) = "https://www.youtube.com/channel/$id"

private fun playlistLink(id: String) = "https://www.youtube.com/playlist?list=$id"

private val MX_LOOPBACK: Inet4Address = InetAddress.getByName("127.0.0.1") as Inet4Address

// ---- The TV, wired as AppContainer wires it ------------------------------------------------------------------------

/**
 * The TV: the real stores (DataStore files in a temporary folder, an in-memory Room database), the real rules
 * (PolicyEngine over the parent's BehaviorSettings, PolicyGate, SessionController, LockController, ParentPinRepository
 * and PhonePin with the PIN brake settings), the real phone side (TvRemoteController with PhoneSettings and
 * PhoneParental, RemoteRevision, RemoteRuntime and its RemoteServer on 127.0.0.1). The backup has every settings
 * section, a restored PIN logs the phones out, a new parent PIN logs them out: as in AppContainer.
 */
class MxTv(dir: File) : Closeable {
    val clock = MutableClock(instantAt("2026-09-28T12:00"))
    val scope = testScope()
    val stores = TestDataStores(File(dir, "stores").apply { mkdirs() })
    val policyStore = stores.open()
    val behaviorStore = stores.open(BehaviorSettings.FILE_NAME)
    val sponsorStore = stores.open(SponsorBlockSettings.FILE_NAME)
    val playbackStore = stores.open(PlaybackSettings.FILE_NAME)
    val remoteStore = stores.open(RemoteSettings.FILE_NAME)
    val behavior = BehaviorSettings(behaviorStore, scope)
    val sponsor = SponsorBlockSettings(sponsorStore)
    val playback = PlaybackSettings(playbackStore, scope)
    val remoteSettings = RemoteSettings(remoteStore)
    val engine = PolicyEngine(clock) { behavior.policyConfig() }
    val db: ShashaDatabase = inMemoryDatabase()
    val source = MxSource()
    val profiles = ProfileRepository(db)
    val usage = UsageRepository(db)
    val overrides = OverridesRepository(policyStore)
    val parentPin = ParentPinRepository(policyStore, clock, PinHasher(iterations = 1_000), lockout = { behavior.pinLockout() })
    val locks = PlaybackLockStore(policyStore, scope)
    val childLetOut = MutableStateFlow(false)
    val active: ActiveProfile
    val session: SessionController
    val gate: PolicyGate

    /** A full disk for the stats: the adult's surrender cannot be stored. */
    @Volatile var failSurrenders = false
    val lock: LockController

    @Volatile var syncs = 0

    /** Runs inside a feed sync request (the last step of an import; it blocks): a case may hold it there. */
    @Volatile var duringSync: () -> Unit = {}
    @Volatile var syncsFinished = 0

    /** Runs at the end of a restore (AppContainer's afterRestore): a case may hold it there. */
    @Volatile var duringRestore: suspend () -> Unit = {}
    @Volatile var restoresFinished = 0
    val subscriptions: SubscriptionRepository
    val lists = ParentalLists(profiles)
    val phonePin = PhonePin(policyStore, clock, lockout = { behavior.pinLockout() })
    val profileChoices = PhoneProfileChoices()
    val backup: BackupManager
    val hub = PlayerHub()
    val screens = MxScreens(this)
    val resources = ApplicationProvider.getApplicationContext<Context>().resources
    val texts = RemoteTexts(resources)
    val revision: RemoteRevision
    val controller: TvRemoteController

    /** The TV's LAN address (null: no network). */
    @Volatile var address: Inet4Address? = MX_LOOPBACK
    lateinit var runtime: RemoteRuntime
    private val extraServers = CopyOnWriteArrayList<RemoteServer>()
    private val viewModels = ViewModelStore()

    /** The player on screen and how to take it away (the TV's own screens decide that). */
    @Volatile var player: FakeActivePlayer? = null
    @Volatile private var detach: () -> Unit = {}

    init {
        runBlocking {
            profiles.ensureOwner(owner().name)
            profiles.upsert(kid())
            parentPin.setup(MX_PIN)
        }
        active = ActiveProfile.create(profiles, policyStore, scope, ownerName = owner().name)
        session = SessionController(engine, active.profile, usage, overrides, scope).also { it.start() }
        gate = PolicyGate(
            engine = engine, active = active, profiles = profiles, session = session, locks = locks, parentPin = parentPin,
            lookup = VideoLookup { id -> LookedUpVideo(source.video(id), null) }, scope = scope,
            surrenders = { usage.recordSurrender(it) }, childLetOut = childLetOut,
        )
        lock = LockController(
            engine = engine,
            store = locks,
            profile = active.profile,
            verifyPin = { gate.verifyParentPin(it) },
            pinLockMillis = { parentPin.remainingLockMillis() },
            surrenders = { event -> if (failSurrenders) throw IOException("disk full") else usage.recordSurrender(event) },
            scope = scope,
            seriousMode = behavior.values.map { it.seriousMode }.distinctUntilChanged(),
        )
        subscriptions = SubscriptionRepository(db, source, clock = { clock.millis() }, onChannelsAdded = { duringSync(); syncs++; syncsFinished++ })
        backup = BackupManager(
            db = db,
            policyStore = policyStore,
            sections = listOf(
                RemoteSettings.backupSection(remoteStore),
                SponsorBlockSettings.backupSection(sponsorStore),
                PlaybackSettings.backupSection(playbackStore),
                BehaviorSettings.backupSection(behaviorStore),
            ),
            clock = clock,
            appVersion = "matrix",
            filesDir = { File(dir, "files") },
            afterRestore = { report ->
                duringRestore()
                syncs++
                if (report.pinChanged) runtime.revokeParentSessions()
                restoresFinished++
            },
        )
        revision = RemoteRevision(
            scope,
            listOf(behavior.values, sponsor.categories, playback.maxQuality, remoteSettings.port, profiles.observeAll(), active.id, subscriptions.observe()),
        )
        controller = TvRemoteController(
            gate = gate,
            profiles = profiles,
            session = session,
            lock = lock,
            lists = ParentalLists(profiles),
            subscriptions = subscriptions,
            source = source,
            phonePin = phonePin,
            backup = backup,
            player = hub,
            display = screens,
            playQueues = PlayQueues(),
            texts = texts,
            main = Dispatchers.Unconfined,
            seriousMode = { behavior.policyConfig().seriousMode },
            profileChoices = profileChoices,
            settings = PhoneSettings(
                behavior = behavior,
                sponsorBlock = sponsor,
                playback = playback,
                remote = remoteSettings,
                resources = resources,
                scope = scope,
                restartRemote = { runtime.restart(announceMs = MOVE_ANNOUNCE_MS) },
                restartDelayMs = 100,
                portAvailable = { runtime.portAvailable(it) },
            ),
            parental = PhoneParental(
                profiles = profiles,
                lists = ParentalLists(profiles),
                usage = usage,
                engine = engine,
                zone = { clock.zone },
                source = source,
                resources = resources,
                errors = texts::sourceError,
                currentId = { active.awaitProfile().id },
            ),
            revision = { revision.value },
            movingTo = { runtime.movingTo.value },
            adultEntryPin = { behavior.policyConfig().adultEntryNeedsPin },
        )
        runtime = RemoteRuntime(
            remoteSettings, controller, MxSilent, findAddress = { address }, phonePin = phonePin,
            pinFailures = { behavior.pinLockout().maxFailures },
        )
        // AppContainer: a new parent PIN logs every phone out of the parent section.
        scope.launch { parentPin.changes.collect { runtime.revokeParentSessions() } }
        seedYouTube()
        runBlocking {
            active.awaitProfile()
            behavior.awaitLoaded()
            // A port nothing else on this machine listens on (8765 may be a QA server).
            remoteSettings.rememberPort(freePort())
            check(runtime.refresh() is RemoteState.Running) { "The server did not start" }
        }
    }

    val port: Int get() = (runtime.state.value as RemoteState.Running).port
    val token: String get() = runtime.pairingToken.value!!

    fun phone(name: String = "A"): MxPhone = MxPhone(this, name, token)

    override fun close() {
        runBlocking { runCatching { runtime.stop() } }
        extraServers.forEach { runCatching { it.stop() } }
        viewModels.clear()
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
    }

    // ---- YouTube ----

    private fun seedYouTube() {
        listOf(
            clip(INSIDE, channelId = ALLOWED_CHANNEL, title = "Allowed cartoon"),
            clip(INSIDE2, channelId = ALLOWED_CHANNEL, title = "Second cartoon"),
            clip(OUTSIDE, channelId = OTHER_CHANNEL, title = "Somewhere else"),
            clip(SHORT_INSIDE, channelId = ALLOWED_CHANNEL, title = "Tiny clip", durationSeconds = 120),
            clip(KEYWORD_INSIDE, channelId = ALLOWED_CHANNEL, title = "فيلم $KEYWORD قصير"),
            clip(BLOCKED_VIDEO, channelId = BLOCKED_CHANNEL, title = "From a blocked channel"),
            clip(LIVE, channelId = OTHER_CHANNEL, title = "Live", durationSeconds = null, live = true),
            clip(PL_VIDEO_1, channelId = OTHER_CHANNEL, title = "Playlist one"),
            clip(PL_VIDEO_2, channelId = OTHER_CHANNEL, title = "Playlist two"),
        ).forEach { source.videos[it.videoId] = it }
        source.channels[ALLOWED_CHANNEL] = ChannelItem(ALLOWED_CHANNEL, "قناة مسموحة", null, 1_000)
        source.channels[OTHER_CHANNEL] = ChannelItem(OTHER_CHANNEL, "قناة أخرى", null, 2_000)
        source.channels[BLOCKED_CHANNEL] = ChannelItem(BLOCKED_CHANNEL, "قناة محظورة", null, 3_000)
        source.channels[NEW_CHANNEL] = ChannelItem(NEW_CHANNEL, "قناة جديدة", null, 4_000)
        source.handles["@allowed"] = ALLOWED_CHANNEL
        source.handles["@newone"] = NEW_CHANNEL
        source.addPlaylist(ALLOWED_PLAYLIST, "قائمة مسموحة", listOf(source.video(PL_VIDEO_1), source.video(PL_VIDEO_2)))
        source.addPlaylist(OTHER_PLAYLIST, "قائمة أخرى", listOf(source.video(INSIDE)))
        source.addPlaylist(NEW_PLAYLIST, "قائمة جديدة", listOf(source.video(INSIDE2)))
        source.addPlaylist(SHORTS_PLAYLIST, "قصيرة فقط", listOf(source.video(SHORT_INSIDE)))
    }

    // ---- The TV's own side ----

    suspend fun current(): Profile = active.awaitProfile()

    /** The TV's picker enters the child profile (no PIN to enter one). */
    suspend fun child(id: String = "kid") {
        assertThat(gate.switchProfile(id, pin = null)).isEqualTo(SwitchResult.Switched)
        settle()
    }

    /** The parent types the PIN on the TV and goes to [id]. */
    suspend fun tvSwitch(id: String, pin: String? = MX_PIN) {
        assertThat(gate.switchProfile(id, pin)).isEqualTo(SwitchResult.Switched)
        settle()
    }

    suspend fun addChild2(): Profile = kid(id = "kid2", name = "ريم").also { profiles.upsert(it) }

    suspend fun addAdult2(): Profile = Profile.newAdult("ضيف", id = "adult2").also { profiles.upsert(it) }

    /** The session computed again (the TV does it every 15 s and on every change). */
    suspend fun settle() {
        session.refresh()
    }

    /** Watched [seconds] today in [profileId] (the usage tracker's FOREGROUND rows). */
    suspend fun used(seconds: Long, profileId: String = "kid") {
        usage.addForeground(profileId, engine.today(), seconds)
        settle()
    }

    /** The TV's clock set to [local] (Asia/Riyadh), e.g. "2026-09-28T23:00". */
    suspend fun at(local: String) {
        clock.set(local)
        settle()
    }

    /** A video starts on the TV in the current profile, as the player screen starts one (it locks while «الوضع الجاد» is on). */
    suspend fun startVideo(videoId: String = INSIDE, playlistId: String? = null, live: Boolean = false): FakeActivePlayer =
        onScreen(videoId, playlistId, current().id, live)

    /** As PlayerScreen does: the player attached to the hub, the start reported to the lock. */
    suspend fun onScreen(videoId: String, playlistId: String?, profileId: String, live: Boolean = false): FakeActivePlayer {
        detach()
        val p = FakeActivePlayer(videoId, live)
        detach = hub.attach(p)
        player = p
        lock.onVideoStarted(profileId, videoId, playlistId)
        return p
    }

    /** The TV's "time up" screen (SessionGate) releases the player: nothing is on screen any more. */
    fun releasePlayer() {
        detach()
        player = null
    }

    suspend fun lockedVideo(videoId: String = INSIDE): FakeActivePlayer {
        val p = startVideo(videoId)
        assertWithMessage("the video should be locked").that(lock.current()).isNotNull()
        return p
    }

    suspend fun serious(on: Boolean) {
        behavior.setSeriousMode(on)
        withTimeout(5_000) { behavior.values.first { it.seriousMode == on } }
        if (!on) withTimeout(5_000) { lock.lock.first { it == null } }
    }

    suspend fun adultEntryPin(on: Boolean) {
        behavior.setAdultEntryPin(on)
        withTimeout(5_000) { behavior.values.first { it.adultEntryPin == on } }
    }

    suspend fun adultEscape(on: Boolean) {
        behavior.setAdultEscape(on)
        withTimeout(5_000) { behavior.values.first { it.adultEscape == on } }
    }

    suspend fun number(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        behavior.setNumber(key, value)
        withTimeout(5_000) { behavior.values.first { BehaviorSettings.decode(behaviorStore.data.first()) == it } }
    }

    suspend fun blockKeyword(word: String = KEYWORD) {
        lists.addKeyword(word)
    }

    suspend fun blockChannel(id: String = BLOCKED_CHANNEL, name: String = "قناة محظورة") {
        lists.blockChannel(ListedChannel(id, name))
    }

    suspend fun quota(profileId: String, minutes: Int?) {
        profiles.update(profileId) { it.copy(dailyQuotaMinutes = minutes) }
        settle()
    }

    suspend fun lockOutTvPin(times: Int = 5) {
        repeat(times) { parentPin.verify(MX_WRONG) }
        assertThat(parentPin.remainingLockMillis()).isGreaterThan(0L)
    }

    /** Wrong PINs counted on the phone's own limiter, without the server's brake (another phone, earlier). */
    suspend fun lockOutPhonePin(times: Int = 5) {
        repeat(times) { phonePin.check(MX_WRONG) }
        assertThat(phonePin.remainingLockMillis()).isGreaterThan(0L)
    }

    suspend fun guard(): GuardDemand = guardDemand(engine, lock.lock, session.state, active.profile, childLetOut).first()

    /** The TV's root (setup, «من يشاهد الآن؟», the profile's screens). */
    fun root(uptime: () -> Long = { 1_000_000L }): RootViewModel {
        val deps = RootDeps(
            profiles, active, parentPin, gate, session, lock = lock, phoneChoices = profileChoices.chosen, childLetOut = childLetOut,
            behavior = behavior.values,
        )
        return ViewModelProvider(viewModels, viewModelFactory { initializer { RootViewModel({ deps }, elapsed = uptime) } })[RootViewModel::class.java]
    }

    /** Another server process over the same stores: what an app restart brings up (its PIN brake starts from nothing). */
    fun restartedServer(): Pair<RemoteServer, Int> {
        val restartedController = TvRemoteController(
            gate = gate, profiles = profiles, session = session, lock = lock, lists = ParentalLists(profiles), subscriptions = subscriptions,
            source = source, phonePin = PhonePin(policyStore, clock, lockout = { behavior.pinLockout() }), backup = backup, player = hub,
            display = screens, playQueues = PlayQueues(), texts = texts, main = Dispatchers.Unconfined,
        )
        val server = RemoteServer(restartedController, { behavior.pinLockout().maxFailures }) { token }
        extraServers += server
        return server to server.start(MX_LOOPBACK, 0)
    }

    suspend fun waitFor(what: String, timeoutMs: Long = 5_000, check: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!check()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    companion object {
        const val MOVE_ANNOUNCE_MS = 400L

        fun freePort(): Int = ServerSocket(0, 50, MX_LOOPBACK).use { it.localPort }
    }
}

private object MxSilent : Announcer {
    override val name: String? = null

    override fun register(port: Int) = Unit

    override fun unregister() = Unit
}

/**
 * The TV's screens (the edge behind [RemoteDisplay]): [answer] is how the TV is (standby, background, picker...).
 * Like NavigationRequests, only the screens of the profile a link was checked for open it ([beforeOpening] lets a
 * case switch profile meanwhile). A player opens as PlayerScreen opens one (attached, its start reported to the lock).
 */
class MxScreens(private val tv: MxTv) : RemoteDisplay {
    val opened = CopyOnWriteArrayList<Pair<Screen, String>>()

    @Volatile var answer = ShowResult.SHOWN

    @Volatile var beforeOpening: suspend () -> Unit = {}

    override suspend fun show(destination: Screen, profileId: String): ShowResult {
        beforeOpening()
        if (answer != ShowResult.SHOWN) return answer
        if (tv.current().id != profileId) return ShowResult.PROFILE_CHANGED
        opened += destination to profileId
        if (destination is Screen.Player) tv.onScreen(destination.videoId, destination.playlistId, profileId)
        return ShowResult.SHOWN
    }

    val screens: List<Screen> get() = opened.map { it.first }
}

/** YouTube as maps, with a few hooks (a slow search, calls counted). */
class MxSource : VideoSource {
    override val extractorVersion = "matrix"
    val videos = ConcurrentHashMap<String, VideoItem>()
    val channels = ConcurrentHashMap<String, ChannelItem>()
    val handles = ConcurrentHashMap<String, String>()
    val playlists = ConcurrentHashMap<String, PlaylistPage>()

    @Volatile var searchResults: List<SourceItem> = emptyList()

    @Volatile var searchFailure: SourceException? = null

    /** Runs inside a search before it answers (e.g. the TV switches profile meanwhile). */
    @Volatile var duringSearch: suspend () -> Unit = {}

    @Volatile var searchDelayMs = 0L
    val searches = CopyOnWriteArrayList<String>()

    /** Every lookup that would reach YouTube (search, channel, playlist, resolve). */
    val calls = CopyOnWriteArrayList<String>()

    fun video(id: String): VideoItem =
        videos[id] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", id, null)

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        searches += query
        calls += "search"
        duringSearch()
        if (searchDelayMs > 0) delay(searchDelayMs)
        searchFailure?.let { throw it }
        return ItemsPage(searchResults, next = null)
    }

    override suspend fun channel(channelId: String): ChannelDetails {
        calls += "channel"
        return channels[channelId]?.let { ChannelDetails(it, bannerUrl = null, description = null) }
            ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", channelId, null)
    }

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = ItemsPage(emptyList(), null)

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        calls += "playlist"
        return playlists[playlistId] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", playlistId, null)
    }

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails =
        throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NotInTests", "StreamDetails cannot be built outside :youtube", null)

    override suspend fun recentVideos(channelId: String): List<VideoItem> = emptyList()

    override fun parseLink(text: String): YouTubeLink? {
        val t = text.trim()
        if (!Regex("^(https?://)?([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be)(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val list = Regex("[?&]list=([0-9A-Za-z_-]+)").find(t)?.groupValues?.get(1)
        val start = Regex("[?&#]t=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
        Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/)([0-9A-Za-z_-]{11})").find(t)?.let { return YouTubeLink.Video(it.groupValues[1], start, list) }
        if (list != null) return YouTubeLink.Playlist(list)
        Regex("/channel/(UC[0-9A-Za-z_-]{22})").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        Regex("youtube\\.com/(@[^/?#\\s]+)").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        return null
    }

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String {
        calls += "resolve"
        return if (link.raw.startsWith("UC")) link.raw
        else handles[link.raw] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "UnresolvedChannel", link.raw, null)
    }

    fun addPlaylist(id: String, title: String, items: List<VideoItem>) {
        playlists[id] = PlaylistPage(PlaylistItem(id, title, "Owner", null, items.size.toLong()), items, next = null)
    }
}

// ---- The phone --------------------------------------------------------------------------------------------------------

/** An HTTP answer. */
class MxAnswer(val code: Int, val body: String, val headers: Map<String, List<String>>) {
    val json: JsonElement by lazy { if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) }
    val obj: JsonObject get() = json as? JsonObject ?: JsonObject(emptyMap())
    val arr: JsonArray get() = json.jsonArray
    val ok: Boolean get() = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
    val message: String get() = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val errorCode: String? get() = obj["code"]?.jsonPrimitive?.contentOrNull
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    override fun toString() = "HTTP $code $body"
}

/**
 * One phone: the pairing token (null: none), its own parent session. Calls go over a real socket to the TV's port
 * (or [port]), one connection each, never retried.
 */
class MxPhone(private val tv: MxTv, val name: String, var token: String?) {
    var session: String? = null
    var port: Int? = null

    suspend fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = "application/json",
        headers: Map<String, String> = emptyMap(),
        bearer: Boolean = true,
        parent: Boolean = true,
    ): MxAnswer = withContext(Dispatchers.IO) {
        val conn = URI("http://127.0.0.1:${port ?: tv.port}$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 60_000
        conn.requestMethod = method
        conn.setRequestProperty("Connection", "close")
        if (bearer) token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (parent) session?.let { conn.setRequestProperty("X-Parent-Session", it) }
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            conn.outputStream.use { it.write(body) }
        } else if (method == "POST") {
            // An empty body, buffered: in streaming mode HttpURLConnection drops the body of a 401.
            conn.doOutput = true
            conn.outputStream.close()
        }
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            MxAnswer(code, stream?.use { it.readBytes().decodeToString() }.orEmpty(), conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }

    suspend fun get(path: String, headers: Map<String, String> = emptyMap()) = call("GET", path, headers = headers)

    suspend fun post(path: String, json: String? = null) = call("POST", path, json?.toByteArray())

    suspend fun status(): JsonObject {
        val a = get("/api/status")
        assertWithMessage("status: $a").that(a.code).isEqualTo(200)
        return a.obj
    }

    suspend fun login(pin: String = MX_PIN): MxAnswer {
        val a = post("/api/parent/login", """{"pin":"$pin"}""")
        if (a.code == 200 && a.ok) session = a.obj["session"]!!.jsonPrimitive.content
        return a
    }

    suspend fun loggedIn(): MxPhone {
        val a = login()
        assertWithMessage("login: $a").that(a.code).isEqualTo(200)
        return this
    }

    suspend fun play(url: String) = post("/api/play", """{"url":${JsonPrimitive(url)}}""")

    suspend fun control(action: String, seconds: Int = 0) = post("/api/control", """{"action":"$action","seconds":$seconds}""")

    suspend fun switchTo(id: String, pin: String? = null) =
        post("/api/profile", if (pin == null) """{"id":"$id"}""" else """{"id":"$id","pin":"$pin"}""")

    suspend fun setting(key: String, value: String, base: String? = null) =
        post("/api/parent/settings", """{"key":"$key","value":$value${base?.let { ""","base":$it""" } ?: ""}}""")

    suspend fun settingsPage(): JsonObject = get("/api/parent/settings").obj

    suspend fun profilesPage(): JsonObject = get("/api/parent/profiles").obj
}

// ---- Assertions ---------------------------------------------------------------------------------------------------------

/** HTTP 200 with ok true (and [message] when given). */
private fun MxAnswer.isOkWith(message: String? = null): MxAnswer {
    assertWithMessage("$this").that(code).isEqualTo(200)
    assertWithMessage("$this").that(ok).isTrue()
    if (message != null) assertWithMessage("$this").that(this.message).isEqualTo(message)
    return this
}

/** A refusal by the TV's rules: HTTP 200, ok false, [message]. */
private fun MxAnswer.isRefusedWith(message: String? = null): MxAnswer {
    assertWithMessage("$this").that(code).isEqualTo(200)
    assertWithMessage("$this").that(ok).isFalse()
    if (message != null) assertWithMessage("$this").that(this.message).isEqualTo(message)
    return this
}

/** An HTTP error with [errorCode] (and [message] when given). */
private fun MxAnswer.isError(http: Int, errorCode: String? = null, message: String? = null): MxAnswer {
    assertWithMessage("$this").that(code).isEqualTo(http)
    if (errorCode != null) assertWithMessage("$this").that(this.errorCode).isEqualTo(errorCode)
    if (message != null) assertWithMessage("$this").that(this.message).isEqualTo(message)
    return this
}

/** Refused one way or the other: an HTTP error, or 200 with ok false. Never 500. */
private fun MxAnswer.isNotAccepted(): MxAnswer {
    assertWithMessage("$this").that(code).isNotEqualTo(500)
    assertWithMessage("$this").that(code == 200 && ok).isFalse()
    return this
}

private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull

/** The setting [key] of a settings page (or an answer carrying one). */
private fun JsonObject.setting(key: String): JsonObject =
    (this["settings"]?.takeUnless { it is JsonNull }?.jsonObject ?: this).getValue("groups").jsonArray
        .flatMap { it.jsonObject.getValue("items").jsonArray }.map { it.jsonObject }.first { it.str("key") == key }

// ---- Server texts (the :remote wire contract) -------------------------------------------------------------------------

private object ServerTexts {
    const val PAIRING = "رابط الاقتران غير صالح. امسح رمز QR الظاهر على الشاشة من جديد."
    const val PARENT_REQUIRED = "سجّل الدخول إلى قسم الأب أولاً."
    const val PARENT_EXPIRED = "انتهت جلسة قسم الأب. أدخل رمز PIN من جديد."
    const val PARENT_WELCOME = "أهلاً بك في قسم الأب."
    const val LOGGED_OUT = "خرجت من قسم الأب."
    const val BAD_REQUEST = "الطلب غير صالح."
    const val SEARCH_DISABLED = "البحث غير متاح في هذا الملف."
    const val EMPTY_QUERY = "اكتب ما تريد البحث عنه."
    const val QUERY_TOO_LONG = "نص البحث طويل جداً. اختصره ثم حاول من جديد."
    const val VIDEO_LOCKED = "الوضع الجاد: الفيديو يُشاهَد حتى نهايته. يُنهيه الأب من قسم الأب."
    const val EMPTY_LINK = "الصق رابط يوتيوب أولاً."
    const val EMPTY_PIN = "أدخل رمز PIN."
    const val BAD_KEYWORD = "اكتب كلمة من 1 إلى 100 حرف."
    const val BAD_NAME = "اكتب اسماً للملف."
    const val EMPTY_FILE = "الملف فارغ."
    const val NO_FILE = "لم يصل أي ملف."
    const val TAKEOUT_TYPE = "اختر ملف subscriptions.csv أو ملف Takeout المضغوط (zip)."
    const val TAKEOUT_NOT_FOUND = "لم أجد ملف subscriptions.csv داخل الملف المضغوط."
    const val TAKEOUT_BROKEN = "تعذّرت قراءة الملف المضغوط."

    fun wrongPin(left: Int) = when {
        left <= 0 -> "رمز PIN غير صحيح."
        left == 1 -> "رمز PIN غير صحيح. بقيت محاولة واحدة."
        left == 2 -> "رمز PIN غير صحيح. بقيت محاولتان."
        left <= 10 -> "رمز PIN غير صحيح. بقيت $left محاولات."
        else -> "رمز PIN غير صحيح. بقيت $left محاولة."
    }
}

/** Every parent route (method, path, a valid body). */
private val MX_PARENT_ROUTES = listOf(
    Triple("POST", "/api/parent/quota", """{"profileId":"kid","minutes":30}"""),
    Triple("POST", "/api/parent/unlock-time", """{"extraMinutes":15}"""),
    Triple("POST", "/api/parent/unlock-video", null),
    Triple("GET", "/api/parent/allowlist?profileId=kid", null),
    Triple("POST", "/api/parent/allowlist/add", """{"profileId":"kid","link":"${channelLink(NEW_CHANNEL)}"}"""),
    Triple("POST", "/api/parent/allowlist/remove", """{"profileId":"kid","id":"$ALLOWED_CHANNEL"}"""),
    Triple("POST", "/api/parent/takeout", "Channel Id,Channel Url,Channel Title\n$NEW_CHANNEL,http://www.youtube.com/channel/$NEW_CHANNEL,قناة\n"),
    Triple("GET", "/api/parent/subscriptions", null),
    Triple("POST", "/api/parent/subscriptions/add", """{"link":"${channelLink(NEW_CHANNEL)}"}"""),
    Triple("POST", "/api/parent/subscriptions/remove", """{"channelId":"$OTHER_CHANNEL"}"""),
    Triple("GET", "/api/parent/backup", null),
    Triple("POST", "/api/parent/backup", """{"app":"shasha"}"""),
    Triple("GET", "/api/parent/settings", null),
    Triple("POST", "/api/parent/settings", """{"key":"serious_mode","value":false}"""),
    Triple("POST", "/api/parent/settings/reset", """{"group":null}"""),
    Triple("GET", "/api/parent/profiles", null),
    Triple("POST", "/api/parent/profile/create", """{"name":"ليلى","avatar":"star"}"""),
    Triple("POST", "/api/parent/profile/edit", """{"profileId":"kid","name":"سارة٢"}"""),
    Triple("POST", "/api/parent/profile/delete", """{"profileId":"kid"}"""),
    Triple("POST", "/api/parent/profile/flags", """{"profileId":"kid","hideShorts":false}"""),
    Triple("POST", "/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":1200}"""),
    Triple("GET", "/api/parent/blocklist", null),
    Triple("POST", "/api/parent/blocklist/add", """{"kind":"keyword","value":"$KEYWORD"}"""),
    Triple("POST", "/api/parent/blocklist/remove", """{"kind":"keyword","value":"$KEYWORD"}"""),
    Triple("GET", "/api/parent/stats", null),
)

/** What the TV keeps that a refused parent write must not have changed. */
private suspend fun MxTv.snapshot(): String = buildString {
    append(profiles.all().joinToString { PolicyJsonText.of(it) })
    append(behavior.current())
    append(sponsor.categories.first())
    append(playback.current())
    append(remoteSettings.port.first())
    append(subscriptions.observe().first().map { it.channelId })
    append(overrides.current())
    append(lock.current())
}

private object PolicyJsonText {
    fun of(p: Profile) = com.ahmadnayfeh.shasha.policy.PolicyJson.json.encodeToString(Profile.serializer(), p)
}

private fun csvOf(vararg channels: Pair<String, String>): String = buildString {
    append("Channel Id,Channel Url,Channel Title\n")
    channels.forEach { (id, name) -> append("$id,http://www.youtube.com/channel/$id,$name\n") }
}

private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun multipart(fileName: String, bytes: ByteArray, boundary: String = "----matrix"): Pair<ByteArray, String> {
    val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n"
    val tail = "\r\n--$boundary--\r\n"
    return (head.toByteArray() + bytes + tail.toByteArray()) to "multipart/form-data; boundary=$boundary"
}

private const val UC_A = "UCaaaaaaaaaaaaaaaaaaaaa1"
private const val UC_B = "UCbbbbbbbbbbbbbbbbbbbbb2"
private const val UC_C = "UCccccccccccccccccccccc3"
private const val UC_D = "UCddddddddddddddddddddd4"

// ======================================================================================================================
// The rows. Ids, preconditions and expectations as in docs/dev/phone-tv-scenarios.md.
// ======================================================================================================================

object MxRows {
    fun all(): List<MxRow> =
        tokenRows() + sessionRows() + validationRows() + statusRows() + searchRows() + linkRows() + controlRows() + profileRows() +
            loginRows() + quotaRows() + timeRows() + unlockRows() + allowlistRows() + subscriptionRows() + takeoutRows() + backupRows() +
            settingsRows() + resetRows() + parentProfileRows() + blocklistRows() + tvEventRows() + twoPhoneRows() + networkRows()
}

// ---- T: the pairing token ----------------------------------------------------------------------------------------------

private fun tokenRows() = listOf(
    row("T-01", "no token, a browser asking for the page: the unpaired page, 401") {
        val a = phone().apply { token = null }.get("/", mapOf("Accept" to "text/html,application/xhtml+xml"))
        assertThat(a.code).isEqualTo(401)
        assertThat(a.header("Content-Type")).contains("text/html")
        assertThat(a.body).contains("QR")
    },
    row("T-02", "no token, not a browser: 401 pairing JSON") {
        phone().apply { token = null }.get("/", mapOf("Accept" to "application/json")).isError(401, "pairing", ServerTexts.PAIRING)
    },
    row("T-03", "wrong token: status 401 pairing") {
        phone().apply { token = "wrong-token-wrong-token-wrong-tok" }.get("/api/status").isError(401, "pairing", ServerTexts.PAIRING)
    },
    row("T-04", "wrong token: a link is refused and the TV shows nothing") {
        phone().apply { token = "wrong" }.play(watch(OUTSIDE)).isError(401, "pairing")
        assertThat(screens.opened).isEmpty()
        assertThat(hub.active.value).isNull()
        assertThat(lock.current()).isNull()
    },
    row("T-05", "wrong token with a valid parent session: 401 pairing, nothing changes") {
        val good = phone().loggedIn()
        val bad = phone("bad").apply { token = "wrong"; session = good.session }
        bad.post("/api/parent/quota", """{"profileId":"kid","minutes":15}""").isError(401, "pairing")
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)
    },
    row("T-06", "wrong token: the icon is refused") {
        assertThat(phone().apply { token = "wrong" }.get("/icon.png").code).isEqualTo(401)
    },
    row("T-07", "token regenerated on the TV: every phone 401 at once, sessions revoked, PIN counts forgotten") {
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        repeat(3) { phone("C").login(MX_WRONG) }
        assertThat(phonePin.status().wrong).isEqualTo(3)
        val fresh = runtime.regenerateToken()
        a.get("/api/status").isError(401, "pairing")
        b.get("/api/parent/settings").isError(401, "pairing")
        // Re-paired with the new QR: the old sessions are gone, the counts too.
        a.token = fresh
        a.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(phonePin.status().wrong).isEqualTo(0)
        a.login().isOkWith(ServerTexts.PARENT_WELCOME)
    },
    row("T-08", "the token as ?t= or as a Bearer header") {
        val p = phone()
        assertThat(p.call("GET", "/api/status?t=${p.token}", bearer = false).code).isEqualTo(200)
        assertThat(p.get("/api/status").code).isEqualTo(200)
    },
    row("T-09", "an unknown path with the token: 404") {
        phone().get("/no-such-path").isError(404, "not_found")
    },
    row("T-10", "the page itself: no-cache, CSP, no referrer, no frames") {
        val p = phone()
        val a = p.call("GET", "/?t=${p.token}", bearer = false, headers = mapOf("Accept" to "text/html"))
        assertThat(a.code).isEqualTo(200)
        assertThat(a.header("Content-Type")).contains("text/html")
        assertThat(a.header("Cache-Control")).contains("no-store")
        assertThat(a.header("Content-Security-Policy")).contains("script-src")
        assertThat(a.header("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(a.header("X-Frame-Options")).isEqualTo("DENY")
    },
)

// ---- PS: the parent session --------------------------------------------------------------------------------------------

private suspend fun MxTv.everyParentRoute(phone: MxPhone, check: (String, MxAnswer) -> Unit) {
    for ((method, path, body) in MX_PARENT_ROUTES) {
        val contentType = if (path.endsWith("/takeout")) "text/csv" else "application/json"
        val a = phone.call(method, path, body?.toByteArray(), contentType)
        check("$method $path", a)
    }
}

private fun sessionRows() = listOf(
    row("PS-01", "no parent session: 401 on every parent route, nothing changes") {
        val before = snapshot()
        everyParentRoute(phone()) { route, a ->
            assertWithMessage("$route: $a").that(a.code).isEqualTo(401)
            assertWithMessage("$route: $a").that(a.errorCode).isEqualTo("parent_session")
            assertWithMessage("$route: $a").that(a.message).isEqualTo(ServerTexts.PARENT_REQUIRED)
        }
        assertThat(snapshot()).isEqualTo(before)
    },
    row("PS-02", "an unknown session: 403 on every parent route, nothing changes") {
        val before = snapshot()
        everyParentRoute(phone().apply { session = "made-up-session-id" }) { route, a ->
            assertWithMessage("$route: $a").that(a.code).isEqualTo(403)
            assertWithMessage("$route: $a").that(a.errorCode).isEqualTo("parent_session")
            assertWithMessage("$route: $a").that(a.message).isEqualTo(ServerTexts.PARENT_EXPIRED)
        }
        assertThat(snapshot()).isEqualTo(before)
    },
    row("PS-05", "a session that ended mid-action: the write is refused and changes nothing; after a login the same write works") {
        val p = phone().loggedIn()
        runtime.revokeParentSessions() // the session ends while the page is open (30 min idle, a new PIN...)
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":15}""").isError(403, "parent_session")
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)
        p.loggedIn().post("/api/parent/quota", """{"profileId":"kid","minutes":15}""").isOkWith()
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(15)
    },
    row("PS-06", "the parent PIN changed on the TV: every phone's session ends") {
        val p = phone().loggedIn()
        assertThat(parentPin.change(MX_PIN, "8642")).isEqualTo(PinResult.Success)
        waitFor("the sessions revoked") { p.get("/api/parent/settings").code == 403 }
        p.login(MX_PIN).isError(401, "wrong_pin")
        p.login("8642").isOkWith()
    },
    row("PS-07", "a restore that changes the PIN hash ends every session") {
        val p = phone().loggedIn()
        val other = phone("B").loggedIn()
        val exported = p.get("/api/parent/backup").body
        assertThat(parentPin.change(MX_PIN, "8642")).isEqualTo(PinResult.Success)
        waitFor("the sessions revoked by the PIN change") { p.get("/api/parent/settings").code == 403 }
        p.login("8642").isOkWith()
        other.login("8642").isOkWith()
        // The backup holds the old PIN's hash: restoring it changes the PIN back and logs every phone out.
        p.call("POST", "/api/parent/backup", exported.toByteArray()).isOkWith()
        p.get("/api/parent/settings").isError(403, "parent_session")
        other.get("/api/parent/settings").isError(403, "parent_session")
        p.login(MX_PIN).isOkWith()
    },
    row("PS-08", "a 9th login drops the least recently used session (8 at most)") {
        val first = phone("1").loggedIn()
        val others = (2..9).map { phone("$it").loggedIn() }
        first.get("/api/parent/settings").isError(403, "parent_session")
        others.forEach { assertThat(it.get("/api/parent/settings").code).isEqualTo(200) }
    },
    row("PS-09", "one phone logging out leaves the other's session") {
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        a.post("/api/parent/logout").isOkWith(ServerTexts.LOGGED_OUT)
        a.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(b.get("/api/parent/settings").code).isEqualTo(200)
    },
)

// ---- V: request validation ---------------------------------------------------------------------------------------------

private fun validationRows() = listOf(
    row("V-01", "not JSON / a field missing: 400, nothing called") {
        val p = phone().loggedIn()
        p.post("/api/play", "not json").isError(400, "bad_request", ServerTexts.BAD_REQUEST)
        p.post("/api/play", "{}").isError(400, "bad_request")
        p.post("/api/parent/allowlist/add", """{"profileId":"kid"}""").isError(400, "bad_request")
        assertThat(screens.opened).isEmpty()
    },
    row("V-02", "a JSON body over 16 KB: 413") {
        phone().post("/api/search", """{"q":"${"x".repeat(17 * 1024)}"}""").isError(413, "too_large")
    },
    row("V-03", "search: empty or longer than 200") {
        val p = phone()
        p.post("/api/search", """{"q":"  "}""").isError(400, "bad_request", ServerTexts.EMPTY_QUERY)
        p.post("/api/search", """{"q":"${"ب".repeat(201)}"}""").isError(400, "bad_request", ServerTexts.QUERY_TOO_LONG)
        assertThat(source.searches).isEmpty()
    },
    row("V-04", "play: empty or longer than 2048") {
        val p = phone()
        p.play("   ").isError(400, "bad_request", ServerTexts.EMPTY_LINK)
        p.play("https://youtu.be/" + "a".repeat(2100)).isError(400, "bad_request")
    },
    row("V-05", "control: SEEK_BY 0 or over a day, an unknown action") {
        val p = phone()
        startVideo(OUTSIDE)
        p.control("SEEK_BY", 0).isError(400, "bad_request")
        p.control("SEEK_BY", 86_401).isError(400, "bad_request")
        p.post("/api/control", """{"action":"EJECT"}""").isError(400, "bad_request")
        assertThat(player!!.commands).isEmpty()
    },
    row("V-06", "profile: blank id, a PIN longer than 32") {
        val p = phone()
        p.post("/api/profile", """{"id":" "}""").isError(400, "bad_request")
        p.switchTo("kid", "1".repeat(33)).isError(400, "bad_request")
        assertThat(current().id).isEqualTo("adult")
    },
    row("V-07", "login: empty PIN, longer than 32") {
        val p = phone()
        p.login("").isError(400, "bad_request", ServerTexts.EMPTY_PIN)
        p.login("1".repeat(33)).isError(400, "bad_request")
        assertThat(phonePin.status().wrong).isEqualTo(0)
    },
    row("V-08", "quota: out of 0..1440, or the minutes left out (never 'unlimited' by default)") {
        val p = phone().loggedIn()
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":-1}""").isError(400)
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":1441}""").isError(400)
        p.post("/api/parent/quota", """{"profileId":"kid"}""").isError(400)
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)
    },
    row("V-09", "unlock-time: 0, over 1440, or left out (never 'rest of today' by default)") {
        val p = phone().loggedIn()
        child()
        used(3600)
        p.post("/api/parent/unlock-time", """{"extraMinutes":0}""").isError(400)
        p.post("/api/parent/unlock-time", """{"extraMinutes":1441}""").isError(400)
        p.post("/api/parent/unlock-time", "{}").isError(400)
        assertThat(session.current()).isEqualTo(SessionState.TimeUp)
    },
    row("V-10", "schedule: one bound only, a bound of 1440") {
        val p = phone().loggedIn()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":null}""").isError(400)
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":1440}""").isError(400)
        assertThat(profiles.get("kid")!!.schedule!!.allowedFromMinute).isEqualTo(7 * 60)
    },
    row("V-11", "takeout: empty, over 20 MB, multipart without a file") {
        val p = phone().loggedIn()
        p.call("POST", "/api/parent/takeout", ByteArray(0), "text/csv").isError(400, "bad_request", ServerTexts.EMPTY_FILE)
        p.call("POST", "/api/parent/takeout", ByteArray(20 * 1024 * 1024 + 1), "text/csv").isError(413, "too_large")
        val boundary = "----nofile"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello\r\n--$boundary--\r\n".toByteArray()
        p.call("POST", "/api/parent/takeout", body, "multipart/form-data; boundary=$boundary").isError(400, "bad_request", ServerTexts.NO_FILE)
    },
    row("V-12", "backup upload: empty, over 10 MB") {
        val p = phone().loggedIn()
        p.call("POST", "/api/parent/backup", ByteArray(0)).isError(400, "bad_request")
        p.call("POST", "/api/parent/backup", ByteArray(10 * 1024 * 1024 + 1)).isError(413, "too_large")
    },
    row("V-13", "settings: a blank key, a key over 64, a blank reset group") {
        val p = phone().loggedIn()
        p.setting(" ", "true").isError(400)
        p.setting("k".repeat(65), "true").isError(400)
        p.post("/api/parent/settings/reset", """{"group":" "}""").isError(400)
    },
    row("V-14", "blocklist: an unknown kind, an empty or too long keyword") {
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/add", """{"kind":"colour","value":"x"}""").isError(400)
        p.post("/api/parent/blocklist/add", """{"kind":"keyword","value":"  "}""").isError(400, "bad_request", ServerTexts.BAD_KEYWORD)
        p.post("/api/parent/blocklist/add", """{"kind":"keyword","value":"${"ب".repeat(101)}"}""").isError(400, "bad_request", ServerTexts.BAD_KEYWORD)
        assertThat(profiles.get("kid")!!.blocklist.titleKeywords).isEmpty()
    },
    row("V-15", "profile create/edit: an empty name, a bad avatar key, nothing to change") {
        val p = phone().loggedIn()
        p.post("/api/parent/profile/create", """{"name":"  ","avatar":"star"}""").isError(400, "bad_request", ServerTexts.BAD_NAME)
        p.post("/api/parent/profile/create", """{"name":"ليلى","avatar":"Not A Key!"}""").isError(400)
        p.post("/api/parent/profile/edit", """{"profileId":"kid"}""").isError(400)
        assertThat(profiles.all()).hasSize(2)
    },
)

// ---- ST: the status the pages poll ---------------------------------------------------------------------------------------

private fun statusRows() = listOf(
    row("ST-01", "adult, no limits, nothing playing") {
        val s = phone().status()
        assertThat(s.str("profileId")).isEqualTo("adult")
        assertThat(s.str("profileKind")).isEqualTo("ADULT")
        assertThat(s.bool("canSearch")).isTrue()
        assertThat(s["nowPlaying"]).isEqualTo(JsonNull)
        assertThat(s.int("timeLeftMinutes")).isNull()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.bool("videoLocked")).isFalse()
        assertThat(s.bool("seriousMode")).isTrue()
    },
    row("ST-02", "child, nothing used at noon: 60 minutes, no search") {
        child()
        val s = phone().status()
        assertThat(s.str("profileKind")).isEqualTo("CHILD")
        assertThat(s.bool("canSearch")).isFalse()
        assertThat(s.int("timeLeftMinutes")).isEqualTo(60)
        assertThat(s.str("sessionState")).isEqualTo("OK")
    },
    row("ST-03", "child with 4 minutes left: WARNING") {
        child()
        used(56 * 60)
        val s = phone().status()
        assertThat(s.str("sessionState")).isEqualTo("WARNING")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(4)
    },
    row("ST-04", "30 seconds left reads 1 minute, never 0") {
        child()
        used(60 * 60 - 30)
        val s = phone().status()
        assertThat(s.int("timeLeftMinutes")).isEqualTo(1)
        assertThat(s.str("sessionState")).isEqualTo("WARNING")
    },
    row("ST-05", "child's quota used up: TIME_UP, 0") {
        child()
        used(60 * 60)
        val s = phone().status()
        assertThat(s.str("sessionState")).isEqualTo("TIME_UP")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(0)
    },
    row("ST-06", "child at 23:00: OUTSIDE_SCHEDULE, 0") {
        child()
        at("2026-09-28T23:00")
        val s = phone().status()
        assertThat(s.str("sessionState")).isEqualTo("OUTSIDE_SCHEDULE")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(0)
    },
    row("ST-07", "child at 21:50: 10 minutes (bedtime nearer than the quota)") {
        child()
        at("2026-09-28T21:50")
        val s = phone().status()
        assertThat(s.int("timeLeftMinutes")).isEqualTo(10)
        assertThat(s.str("sessionState")).isEqualTo("OK")
    },
    row("ST-08", "playing: now playing with position and duration") {
        serious(false)
        startVideo(OUTSIDE)
        val np = phone().status().getValue("nowPlaying").jsonObject
        assertThat(np.str("videoId")).isEqualTo(OUTSIDE)
        assertThat(np.str("title")).isNotEmpty()
        assertThat(np.long("positionSeconds")).isEqualTo(42)
        assertThat(np.long("durationSeconds")).isEqualTo(600)
        assertThat(np.bool("isPlaying")).isTrue()
        assertThat(np.bool("isLive")).isFalse()
    },
    row("ST-09", "paused: isPlaying false") {
        serious(false)
        startVideo(OUTSIDE).playing = false
        assertThat(phone().status().getValue("nowPlaying").jsonObject.bool("isPlaying")).isFalse()
    },
    row("ST-10", "live: no duration, isLive") {
        startVideo(LIVE, live = true)
        val np = phone().status().getValue("nowPlaying").jsonObject
        assertThat(np["durationSeconds"]).isEqualTo(JsonNull)
        assertThat(np.bool("isLive")).isTrue()
    },
    row("ST-11", "Serious Mode on, a video started: videoLocked") {
        startVideo(OUTSIDE)
        assertThat(phone().status().bool("videoLocked")).isTrue()
        assertThat(lock.unlockByParent()).isTrue()
        child()
        startVideo(INSIDE)
        assertThat(phone().status().bool("videoLocked")).isTrue()
    },
    row("ST-12", "Serious Mode off: nothing locks") {
        serious(false)
        startVideo(OUTSIDE)
        val s = phone().status()
        assertThat(s.bool("seriousMode")).isFalse()
        assertThat(s.bool("videoLocked")).isFalse()
    },
    row("ST-13", "a child's locked video, then time up: no player, still locked") {
        child()
        lockedVideo(INSIDE)
        used(3600)
        releasePlayer()
        val s = phone().status()
        assertThat(s.str("sessionState")).isEqualTo("TIME_UP")
        assertThat(s["nowPlaying"]).isEqualTo(JsonNull)
        assertThat(s.bool("videoLocked")).isTrue()
    },
    row("ST-14", "nothing changes: the mark stays") {
        val p = phone()
        val first = p.status().long("rev")
        delay(300)
        assertThat(p.status().long("rev")).isEqualTo(first)
    },
    row("ST-15", "a setting, a profile, a list, a subscription, a switch: the mark moves each time") {
        val p = phone()
        suspend fun moves(what: String, change: suspend () -> Unit) {
            val before = p.status().long("rev")
            change()
            waitFor("rev moves after $what") { p.status().long("rev") != before }
        }
        moves("a setting on the TV") { behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30) }
        moves("a profile edit") { profiles.update("kid") { it.copy(hideShorts = false) } }
        moves("an allowlist") { lists.allowChannel("kid", ListedChannel(NEW_CHANNEL, "قناة جديدة")) }
        moves("a subscription") { subscriptions.add(ChannelItem(NEW_CHANNEL, "قناة جديدة", null, null)) }
        moves("a switch") { child() }
        moves("SponsorBlock") { sponsor.setCategories(emptySet()) }
        moves("the quality") { playback.setMaxQuality(720) }
    },
    row("ST-16", "a port move announced: movingTo") {
        val p = phone()
        assertThat(p.status()["movingTo"]).isEqualTo(JsonNull)
        val target = MxTv.freePort()
        remoteSettings.setPort(target)
        val move = scope.async { runtime.restart(announceMs = 1_500) }
        waitFor("movingTo announced") { runCatching { p.status().int("movingTo") }.getOrNull() == target }
        move.await()
    },
    row("ST-17", "warning_minutes 0: never WARNING") {
        number(BehaviorKeys.WARNING_MINUTES, 0)
        child()
        used(56 * 60)
        assertThat(phone().status().str("sessionState")).isEqualTo("OK")
    },
)

// ---- SE: search -----------------------------------------------------------------------------------------------------------

private fun searchRows() = listOf(
    row("SE-01", "a child: 403, YouTube not asked") {
        child()
        source.searchResults = listOf(source.video(INSIDE))
        phone().post("/api/search", """{"q":"كرتون"}""").isError(403, "rejected", ServerTexts.SEARCH_DISABLED)
        assertThat(source.searches).isEmpty()
    },
    row("SE-02", "the adult: results without blocked channels, blocked words or hidden shorts") {
        blockChannel()
        blockKeyword()
        profiles.update("adult") { it.copy(hideShorts = true) }
        source.searchResults = listOf(
            source.video(OUTSIDE), source.video(BLOCKED_VIDEO), source.video(KEYWORD_INSIDE), source.video(SHORT_INSIDE),
            ChannelItem(OTHER_CHANNEL, "قناة أخرى", null, 2_000), PlaylistItem(OTHER_PLAYLIST, "قائمة أخرى", "Owner", null, 1),
        )
        val a = phone().post("/api/search", """{"q":"x"}""")
        assertThat(a.code).isEqualTo(200)
        val items = a.arr.map { it.jsonObject }
        assertThat(items.map { it.str("id") }).containsExactly(OUTSIDE, OTHER_CHANNEL, OTHER_PLAYLIST).inOrder()
        assertThat(items.map { it.str("type") }).containsExactly("video", "channel", "playlist").inOrder()
    },
    row("SE-03", "nothing found: []") {
        source.searchResults = emptyList()
        val a = phone().post("/api/search", """{"q":"لا شيء"}""")
        assertThat(a.code).isEqualTo(200)
        assertThat(a.arr).isEmpty()
    },
    row("SE-04", "YouTube fails: 403 with the Arabic reason") {
        val e = SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", "x", null)
        source.searchFailure = e
        phone().post("/api/search", """{"q":"x"}""").isError(403, "rejected", texts.sourceError(e))
    },
    row("SE-05", "the adult in time up, outside the schedule or under a locked video still searches (nothing opens)") {
        source.searchResults = listOf(source.video(OUTSIDE))
        quota("adult", 30)
        used(30 * 60, "adult")
        assertThat(phone().post("/api/search", """{"q":"x"}""").code).isEqualTo(200)
        quota("adult", null)
        lockedVideo(OUTSIDE)
        assertThat(phone().post("/api/search", """{"q":"x"}""").code).isEqualTo(200)
        assertThat(screens.opened).isEmpty()
    },
    row("SE-06", "the TV switches to a child during a search: the answer follows the child's rules") {
        source.searchResults = listOf(source.video(OUTSIDE), source.video(INSIDE))
        source.duringSearch = { child() }
        val a = phone().post("/api/search", """{"q":"x"}""")
        assertThat(a.code).isEqualTo(200)
        assertThat(a.arr.map { it.jsonObject.str("id") }).containsExactly(INSIDE)
    },
)

// ---- PL: links from the phone ------------------------------------------------------------------------------------------

private suspend fun MxTv.nothingShown() {
    assertThat(screens.opened).isEmpty()
    assertThat(hub.active.value).isNull()
    assertThat(lock.current()).isNull()
}

private fun MxTv.deny(reason: DenyReason) = texts.deny(reason)

private fun linkRows() = listOf(
    row("PL-01", "child, an allowlisted video: it opens in the child's screens, as a link") {
        child()
        phone().play(watch(INSIDE)).isOkWith()
        assertThat(screens.opened).containsExactly(Screen.Player(INSIDE, fromLink = true) to "kid")
    },
    row("PL-02", "child, a video outside the allowlist: refused, the TV shows nothing") {
        child()
        phone().play(watch(OUTSIDE)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        nothingShown()
    },
    row("PL-03", "child, outside video + &list=<allowlisted playlist>: refused (list= ignored)") {
        child()
        phone().play(watch(OUTSIDE, list = ALLOWED_PLAYLIST)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        nothingShown()
    },
    row("PL-04", "child, allowlisted video + &list=<other playlist>: plays, as a link") {
        child()
        phone().play(watch(INSIDE, list = OTHER_PLAYLIST)).isOkWith()
        assertThat(screens.screens).containsExactly(Screen.Player(INSIDE, playlistId = OTHER_PLAYLIST, fromLink = true))
    },
    row("PL-05", "child hiding shorts, a short of an allowlisted channel: refused") {
        child()
        phone().play(watch(SHORT_INSIDE)).isRefusedWith(deny(DenyReason.SHORTS_HIDDEN))
        nothingShown()
    },
    row("PL-06", "child, an allowlisted video with a blocked word: refused") {
        blockKeyword()
        child()
        phone().play(watch(KEYWORD_INSIDE)).isRefusedWith(deny(DenyReason.BLOCKED_KEYWORD))
        nothingShown()
    },
    row("PL-07", "child, the allowlisted playlist: its first video plays, as the playlist") {
        child()
        phone().play(playlistLink(ALLOWED_PLAYLIST)).isOkWith(texts.get(R.string.remote_play_playlist, "قائمة مسموحة"))
        assertThat(screens.screens).containsExactly(Screen.Player(PL_VIDEO_1, playlistId = ALLOWED_PLAYLIST, playlistIndex = 0))
    },
    row("PL-08", "child, a playlist not allowlisted: refused") {
        child()
        phone().play(playlistLink(OTHER_PLAYLIST)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        nothingShown()
    },
    row("PL-09", "child, an allowlisted playlist whose videos are all hidden: refused") {
        lists.allowPlaylist("kid", com.ahmadnayfeh.shasha.policy.ListedPlaylist(SHORTS_PLAYLIST, "قصيرة فقط"))
        child()
        phone().play(playlistLink(SHORTS_PLAYLIST)).isRefusedWith(texts.get(R.string.remote_playlist_nothing_allowed))
        nothingShown()
    },
    row("PL-10", "child, the allowlisted channel (id or @handle): its page opens") {
        child()
        phone().play(channelLink(ALLOWED_CHANNEL)).isOkWith(texts.get(R.string.remote_play_channel))
        phone().play("https://www.youtube.com/@allowed").isOkWith(texts.get(R.string.remote_play_channel))
        assertThat(screens.screens).containsExactly(Screen.Channel(ALLOWED_CHANNEL), Screen.Channel(ALLOWED_CHANNEL))
    },
    row("PL-11", "child, another channel: refused") {
        child()
        phone().play(channelLink(OTHER_CHANNEL)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        nothingShown()
    },
    row("PL-12", "child, time up, an allowlisted video: refused (time up)") {
        child()
        used(3600)
        phone().play(watch(INSIDE)).isRefusedWith(deny(DenyReason.TIME_UP))
        nothingShown()
    },
    row("PL-13", "child, outside the schedule, an allowlisted video: refused (schedule)") {
        child()
        at("2026-09-28T23:00")
        phone().play(watch(INSIDE)).isRefusedWith(deny(DenyReason.OUTSIDE_SCHEDULE))
        nothingShown()
    },
    row("PL-14", "child, time up, a video outside the allowlist: the content reason first") {
        child()
        used(3600)
        phone().play(watch(OUTSIDE)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
    },
    row("PL-15", "child, time up / outside: an allowlisted playlist and channel are refused for the time") {
        child()
        used(3600)
        phone().play(playlistLink(ALLOWED_PLAYLIST)).isRefusedWith(deny(DenyReason.TIME_UP))
        at("2026-09-28T23:00")
        phone().play(channelLink(ALLOWED_CHANNEL)).isRefusedWith(deny(DenyReason.OUTSIDE_SCHEDULE))
        nothingShown()
    },
    row("PL-16", "child, time up: a channel or playlist outside the allowlist gets the content reason first") {
        child()
        used(3600)
        phone().play(channelLink(OTHER_CHANNEL)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        phone().play(playlistLink(OTHER_PLAYLIST)).isRefusedWith(deny(DenyReason.NOT_IN_ALLOWLIST))
        nothingShown()
    },
    row("PL-17", "a locked video (child, then adult): every new link refused before any lookup") {
        child()
        val p = phone()
        p.play(watch(INSIDE)).isOkWith()
        assertThat(lock.current()!!.unlock).isEqualTo(UnlockMethod.PARENT_PIN)
        val opened = screens.opened.size
        source.calls.clear()
        for (link in listOf(watch(INSIDE), watch(INSIDE2), playlistLink(ALLOWED_PLAYLIST), channelLink(ALLOWED_CHANNEL))) {
            p.play(link).isRefusedWith(deny(DenyReason.LOCKED))
        }
        assertThat(source.calls).isEmpty()
        assertThat(screens.opened).hasSize(opened)
        assertThat(lock.current()!!.videoId).isEqualTo(INSIDE)
        // The adult's lock too.
        assertThat(lock.unlockByParent()).isTrue()
        tvSwitch("adult")
        p.play(watch(OUTSIDE)).isOkWith()
        assertThat(lock.current()!!.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        p.play(watch(PL_VIDEO_1)).isRefusedWith(deny(DenyReason.LOCKED))
    },
    row("PL-18", "adult, any video: it plays") {
        phone().play(watch(OUTSIDE)).isOkWith()
        assertThat(screens.opened).containsExactly(Screen.Player(OUTSIDE, fromLink = true) to "adult")
    },
    row("PL-19", "adult, a blocked channel's video and its channel: refused") {
        blockChannel()
        phone().play(watch(BLOCKED_VIDEO)).isRefusedWith(deny(DenyReason.BLOCKED_CHANNEL))
        phone().play(channelLink(BLOCKED_CHANNEL)).isRefusedWith(deny(DenyReason.BLOCKED_CHANNEL))
        nothingShown()
    },
    row("PL-20", "adult, a playlist and a channel: they open") {
        val p = phone()
        p.play(playlistLink(OTHER_PLAYLIST)).isOkWith()
        serious(false)
        p.play(channelLink(OTHER_CHANNEL)).isOkWith()
        assertThat(screens.screens).containsExactly(Screen.Player(INSIDE, playlistId = OTHER_PLAYLIST, playlistIndex = 0), Screen.Channel(OTHER_CHANNEL)).inOrder()
    },
    row("PL-21", "adult with a quota, time up: refused") {
        quota("adult", 30)
        used(30 * 60, "adult")
        phone().play(watch(OUTSIDE)).isRefusedWith(deny(DenyReason.TIME_UP))
        nothingShown()
    },
    row("PL-22", "a start time: the player starts there (t=0: from the saved place)") {
        serious(false)
        phone().play(watch(OUTSIDE, t = 95)).isOkWith()
        phone().play(watch(OUTSIDE, t = 0)).isOkWith()
        assertThat(screens.screens).containsExactly(
            Screen.Player(OUTSIDE, fromLink = true, startSeconds = 95),
            Screen.Player(OUTSIDE, fromLink = true),
        ).inOrder()
    },
    row("PL-23", "not a YouTube link: refused") {
        phone().play("hello").isRefusedWith(texts.get(R.string.remote_link_not_youtube))
        phone().play("https://example.com/watch?v=abcdefghijk").isRefusedWith(texts.get(R.string.remote_link_not_youtube))
        nothingShown()
    },
    row("PL-24", "a removed video: refused with YouTube's reason") {
        val e = SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", REMOVED, null)
        phone().play(watch(REMOVED)).isRefusedWith(texts.sourceError(e))
        nothingShown()
    },
    row("PL-25", "allowed, the TV in standby") {
        screens.answer = ShowResult.SCREEN_OFF
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_screen_off))
        nothingShown()
    },
    row("PL-26", "allowed, the app in the background without the overlay permission") {
        screens.answer = ShowResult.NOT_IN_FRONT
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_not_in_front))
        nothingShown()
    },
    row("PL-27", "allowed, the TV asks who is watching") {
        screens.answer = ShowResult.NO_PROFILE
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_no_profile))
        nothingShown()
    },
    row("PL-28", "allowed for the adult, the TV switches to the child before it opens: nothing opens") {
        screens.beforeOpening = { child() }
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_profile_changed))
        nothingShown()
        assertThat(current().id).isEqualTo("kid")
    },
    row("PL-29", "allowed, the screens do not answer") {
        screens.answer = ShowResult.FAILED
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_show_failed))
        nothingShown()
    },
    row("PL-30", "Serious Mode on: a video started from the phone locks (adult: escape; child: PIN)") {
        val p = phone()
        p.play(watch(OUTSIDE)).isOkWith()
        assertThat(lock.current()!!.unlock).isEqualTo(UnlockMethod.EXIT_CHALLENGE)
        assertThat(p.status().bool("videoLocked")).isTrue()
        assertThat(lock.unlockByParent()).isTrue()
        child()
        p.play(watch(INSIDE)).isOkWith()
        assertThat(lock.current()!!.unlock).isEqualTo(UnlockMethod.PARENT_PIN)
        assertThat(p.status().bool("videoLocked")).isTrue()
    },
    row("PL-31", "Serious Mode off: nothing locks") {
        serious(false)
        val p = phone()
        p.play(watch(OUTSIDE)).isOkWith()
        assertThat(lock.current()).isNull()
        assertThat(p.status().bool("videoLocked")).isFalse()
    },
    row("PL-32", "an unlocked video playing: another link replaces it") {
        serious(false)
        val p = phone()
        p.play(watch(OUTSIDE)).isOkWith()
        p.play(watch(PL_VIDEO_1)).isOkWith()
        assertThat(hub.active.value!!.videoId).isEqualTo(PL_VIDEO_1)
        // Unlocked by the parent while Serious Mode is on: the same.
        serious(true)
        p.play(watch(OUTSIDE)).isOkWith()
        assertThat(lock.unlockByParent()).isTrue()
        p.play(watch(PL_VIDEO_2)).isOkWith()
        assertThat(hub.active.value!!.videoId).isEqualTo(PL_VIDEO_2)
    },
)

// ---- CT: playback controls ------------------------------------------------------------------------------------------------

private fun controlRows() = listOf(
    row("CT-01", "nothing playing: every command says so") {
        val p = phone()
        for (action in listOf("PLAY", "PAUSE", "TOGGLE", "STOP")) p.control(action).isRefusedWith(texts.get(R.string.remote_nothing_playing))
        p.control("SEEK_BY", 10).isRefusedWith(texts.get(R.string.remote_nothing_playing))
    },
    row("CT-02", "playing, PAUSE") {
        serious(false)
        val v = startVideo(OUTSIDE)
        phone().control("PAUSE").isOkWith()
        assertThat(v.playing).isFalse()
        assertThat(v.commands).containsExactly(PlayerCommand.Pause)
    },
    row("CT-03", "paused, PLAY") {
        serious(false)
        val v = startVideo(OUTSIDE).apply { playing = false }
        phone().control("PLAY").isOkWith()
        assertThat(v.playing).isTrue()
    },
    row("CT-04", "TOGGLE flips either way") {
        serious(false)
        val v = startVideo(OUTSIDE)
        phone().control("TOGGLE").isOkWith()
        assertThat(v.playing).isFalse()
        phone().control("TOGGLE").isOkWith()
        assertThat(v.playing).isTrue()
    },
    row("CT-05", "SEEK_BY plus and minus 10") {
        serious(false)
        val v = startVideo(OUTSIDE)
        phone().control("SEEK_BY", 10).isOkWith()
        phone().control("SEEK_BY", -10).isOkWith()
        assertThat(v.commands).containsExactly(PlayerCommand.SeekBy(10_000), PlayerCommand.SeekBy(-10_000)).inOrder()
    },
    row("CT-06", "live: no seeking, pausing works") {
        val v = startVideo(LIVE, live = true)
        phone().control("SEEK_BY", 10).isRefusedWith(texts.get(R.string.remote_player_live))
        phone().control("PAUSE").isOkWith()
        assertThat(v.playing).isFalse()
    },
    row("CT-07", "ended: play, toggle and seek say so") {
        serious(false)
        startVideo(OUTSIDE).apply { ended = true }
        for (action in listOf("PLAY", "TOGGLE")) phone().control(action).isRefusedWith(texts.get(R.string.remote_player_ended))
        phone().control("SEEK_BY", 10).isRefusedWith(texts.get(R.string.remote_player_ended))
    },
    row("CT-08", "a PIN pad over the video: PLAY waits for it, PAUSE works") {
        child()
        val v = lockedVideo(INSIDE).apply { covered = true; playing = false }
        phone().control("PLAY").isRefusedWith(texts.get(R.string.remote_player_covered))
        assertThat(v.playing).isFalse()
        phone().control("PAUSE").isOkWith()
    },
    row("CT-09", "Serious Mode off: STOP leaves the video") {
        serious(false)
        val v = startVideo(OUTSIDE)
        phone().control("STOP").isOkWith(texts.get(R.string.remote_stopped))
        assertThat(v.left).isEqualTo(1)
    },
    row("CT-10", "a locked video (adult, then child): STOP 403, the player stays") {
        val v = lockedVideo(OUTSIDE)
        phone().control("STOP").isError(403, "rejected", ServerTexts.VIDEO_LOCKED)
        assertThat(v.left).isEqualTo(0)
        assertThat(lock.current()!!.videoId).isEqualTo(OUTSIDE)
        assertThat(lock.unlockByParent()).isTrue()
        child()
        val k = lockedVideo(INSIDE)
        phone().control("STOP").isError(403, "rejected", ServerTexts.VIDEO_LOCKED)
        assertThat(k.left).isEqualTo(0)
    },
    row("CT-11", "a locked video: pause, play, toggle and seek still work") {
        val v = lockedVideo(OUTSIDE)
        val p = phone()
        p.control("PAUSE").isOkWith()
        p.control("PLAY").isOkWith()
        p.control("TOGGLE").isOkWith()
        p.control("SEEK_BY", 30).isOkWith()
        assertThat(v.commands).hasSize(4)
        assertThat(lock.current()).isNotNull()
    },
    row("CT-12", "a locked video skipped to its end stays locked: STOP 403") {
        child()
        val v = lockedVideo(INSIDE)
        lock.onVideoEnded("kid", INSIDE, nextVideoId = null, played = PlayedPart(playedMs = 10_000, leftMs = 600_000))
        v.ended = true
        assertThat(lock.current()).isNotNull()
        phone().control("STOP").isError(403, "rejected")
    },
    row("CT-13", "locked + time up (the player released): STOP 403, PAUSE nothing playing") {
        child()
        lockedVideo(INSIDE)
        used(3600)
        releasePlayer()
        phone().control("STOP").isError(403, "rejected")
        phone().control("PAUSE").isRefusedWith(texts.get(R.string.remote_nothing_playing))
    },
    row("CT-14", "after the parent's unlock: STOP works") {
        val v = lockedVideo(OUTSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/unlock-video").isOkWith()
        p.control("STOP").isOkWith(texts.get(R.string.remote_stopped))
        assertThat(v.left).isEqualTo(1)
    },
)

// ---- PR: profiles and switching -----------------------------------------------------------------------------------------

private suspend fun MxPhone.profileList(): List<JsonObject> {
    val a = get("/api/profiles")
    assertThat(a.code).isEqualTo(200)
    return a.arr.map { it.jsonObject }
}

private fun profileRows() = listOf(
    row("PR-01", "from the adult: every profile, the current one marked, children without a PIN") {
        val list = phone().profileList()
        assertThat(list.map { it.str("id") }).containsExactly("adult", "kid").inOrder()
        val adult = list.first { it.str("id") == "adult" }
        val kid = list.first { it.str("id") == "kid" }
        assertThat(adult.bool("isCurrent")).isTrue()
        assertThat(adult.bool("needsPin")).isFalse()
        assertThat(kid.bool("needsPin")).isFalse()
        assertThat(kid.int("dailyQuotaMinutes")).isEqualTo(60)
    },
    row("PR-02", "from a child: every other profile needs the PIN, whatever adultEntryPin") {
        addChild2()
        child()
        for (aep in listOf(true, false)) {
            adultEntryPin(aep)
            val list = phone().profileList()
            assertThat(list.first { it.str("id") == "adult" }.bool("needsPin")).isTrue()
            assertThat(list.first { it.str("id") == "kid2" }.bool("needsPin")).isTrue()
            assertThat(list.first { it.str("id") == "kid" }.bool("needsPin")).isFalse()
        }
    },
    row("PR-03", "from the adult: a second adult needs the PIN only while adultEntryPin is on") {
        addAdult2()
        assertThat(phone().profileList().first { it.str("id") == "adult2" }.bool("needsPin")).isTrue()
        adultEntryPin(false)
        assertThat(phone().profileList().first { it.str("id") == "adult2" }.bool("needsPin")).isFalse()
    },
    row("PR-04", "adult to child without a PIN: the TV is on the child, the guard holds it") {
        phone().switchTo("kid").isOkWith(texts.get(R.string.remote_profile_switched, "سارة"))
        assertThat(current().id).isEqualTo("kid")
        settle()
        assertThat(guard().enforce).isTrue()
        assertThat(guard().child).isTrue()
    },
    row("PR-05", "the current profile again: said so, no PIN checked or counted") {
        phone().switchTo("adult").isOkWith(texts.get(R.string.remote_profile_current, "الكبار"))
        phone().switchTo("adult", MX_WRONG).isOkWith(texts.get(R.string.remote_profile_current, "الكبار"))
        assertThat(phonePin.status().wrong).isEqualTo(0)
    },
    row("PR-06", "child to adult without a PIN: needs the PIN, nothing counted") {
        child()
        phone().switchTo("adult").isRefusedWith(texts.get(R.string.remote_profile_needs_pin))
        assertThat(current().id).isEqualTo("kid")
        assertThat(phonePin.status().wrong).isEqualTo(0)
    },
    row("PR-07", "child to adult with a wrong PIN: attempts left; the phone counts it, the TV does not") {
        child()
        phone().switchTo("adult", MX_WRONG).isRefusedWith(texts.wrongPin(4))
        assertThat(current().id).isEqualTo("kid")
        assertThat(phonePin.status().wrong).isEqualTo(1)
        assertThat(parentPin.verify(MX_WRONG)).isEqualTo(PinResult.Wrong(4))
    },
    row("PR-08", "adultEntryPin on: child to adult with the PIN; the TV asks for it again") {
        child()
        val root = root()
        waitFor("the picker at start") { root.screen.value is RootScreen.Picker }
        assertThat(root.choose(profiles.get("kid")!!, null)).isEqualTo(com.ahmadnayfeh.shasha.ui.components.PinCheck.Accepted)
        waitFor("the child's screens") { root.screen.value == RootScreen.Browse }
        phone().switchTo("adult", MX_PIN).isOkWith(texts.get(R.string.remote_profile_switched_adult, "الكبار"))
        assertThat(current().id).isEqualTo("adult")
        waitFor("the TV asks who is watching") { root.screen.value == RootScreen.Picker(PickerMode.START) }
        assertThat(root.needsPin(profiles.get("adult")!!)).isTrue()
    },
    row("PR-09", "adultEntryPin off: child to adult with the PIN; no PIN on the TV, and the answer does not promise one") {
        adultEntryPin(false)
        child()
        val root = root()
        waitFor("the picker at start") { root.screen.value is RootScreen.Picker }
        root.choose(profiles.get("kid")!!, null)
        waitFor("the child's screens") { root.screen.value == RootScreen.Browse }
        val a = phone().switchTo("adult", MX_PIN).isOkWith()
        assertThat(current().id).isEqualTo("adult")
        // The TV asks no PIN for the adult now: the phone must not say it will.
        assertThat(a.message).isNotEqualTo(texts.get(R.string.remote_profile_switched_adult, "الكبار"))
        assertThat(a.message).doesNotContain("رمز")
        waitFor("the TV settles") { root.screen.value != RootScreen.Browse || current().isAdult }
        assertThat(root.needsPin(profiles.get("adult")!!)).isFalse()
    },
    row("PR-10", "child to another child: the PIN is needed, then it switches") {
        addChild2()
        child()
        phone().switchTo("kid2").isRefusedWith(texts.get(R.string.remote_profile_needs_pin))
        phone().switchTo("kid2", MX_PIN).isOkWith(texts.get(R.string.remote_profile_switched, "ريم"))
        assertThat(current().id).isEqualTo("kid2")
    },
    row("PR-11", "a locked video: every switch refused, the PIN not even checked") {
        child()
        lockedVideo(INSIDE)
        phone().switchTo("adult", MX_PIN).isRefusedWith(texts.get(R.string.remote_switch_locked))
        assertThat(current().id).isEqualTo("kid")
        assertThat(lock.current()!!.videoId).isEqualTo(INSIDE)
        assertThat(phonePin.status().wrong).isEqualTo(0)
        // The adult's lock.
        assertThat(lock.unlockByParent()).isTrue()
        tvSwitch("adult")
        lockedVideo(OUTSIDE)
        phone().switchTo("kid").isRefusedWith(texts.get(R.string.remote_switch_locked))
        assertThat(current().id).isEqualTo("adult")
    },
    row("PR-12", "an unknown profile") {
        phone().switchTo("nobody").isRefusedWith(texts.get(R.string.remote_profile_not_found))
    },
    row("PR-13", "the phone's count locked out: even the right PIN is refused, and says so") {
        child()
        lockOutPhonePin()
        val a = phone().switchTo("adult", MX_PIN).isRefusedWith()
        assertThat(a.message).isEqualTo(texts.get(R.string.remote_pin_locked, texts.minutesAfter(5)))
        assertThat(current().id).isEqualTo("kid")
    },
    row("PR-14", "the TV's count locked out by a child: the phone's switch with the right PIN works") {
        child()
        lockOutTvPin()
        phone().switchTo("adult", MX_PIN).isOkWith()
        assertThat(current().id).isEqualTo("adult")
    },
    row("PR-15", "the server's brake: after 5 refused PIN switches, 429 without asking the TV") {
        child()
        val p = phone()
        repeat(5) { p.switchTo("adult", MX_WRONG).isRefusedWith() }
        val a = p.switchTo("adult", MX_PIN).isError(429, "locked_out")
        assertThat(a.header("Retry-After")!!.toLong()).isGreaterThan(0L)
        assertThat(current().id).isEqualTo("kid")
    },
    row("PR-16", "a child in time up to the adult with the PIN: no time-up screen for the adult") {
        child()
        used(3600)
        phone().switchTo("adult", MX_PIN).isOkWith()
        settle()
        assertThat(phone().status().str("sessionState")).isEqualTo("OK")
    },
    row("PR-17", "adult to a child whose time is up: the child's time-up screen") {
        used(3600, "kid")
        phone().switchTo("kid").isOkWith()
        settle()
        assertThat(phone().status().str("sessionState")).isEqualTo("TIME_UP")
    },
    row("PR-18", "the TV asks who is watching: the child the phone chose opens there") {
        addChild2()
        child()
        val root = root()
        waitFor("the picker at start") { root.screen.value == RootScreen.Picker(PickerMode.START) }
        // The current child, chosen from the phone.
        phone().switchTo("kid").isOkWith()
        waitFor("the child's screens open") { root.screen.value == RootScreen.Browse }
        // The child's «خروج» with the PIN on the TV: the picker; the phone picks the other child (with the PIN).
        assertThat(root.confirmExit(MX_PIN)).isEqualTo(com.ahmadnayfeh.shasha.ui.components.PinCheck.Accepted)
        waitFor("the picker") { root.screen.value == RootScreen.Picker(PickerMode.FROM_CHILD_EXIT) }
        phone().switchTo("kid2", MX_PIN).isOkWith(texts.get(R.string.remote_profile_switched, "ريم"))
        waitFor("the other child's screens open") { root.screen.value == RootScreen.Browse && current().id == "kid2" }
    },
)

// ---- LG: parent login and logout -------------------------------------------------------------------------------------------

private fun loginRows() = listOf(
    row("LG-01", "the right PIN: a session and the welcome") {
        val a = phone().login().isOkWith(ServerTexts.PARENT_WELCOME)
        assertThat(a.obj.str("session")!!.length).isAtLeast(20)
        assertThat(phonePin.status().wrong).isEqualTo(0)
    },
    row("LG-02", "4 wrong PINs: 401 counting down; the TV's count untouched") {
        val p = phone()
        for (left in listOf(4, 3, 2, 1)) p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(left))
        assertThat(parentPin.verify(MX_WRONG)).isEqualTo(PinResult.Wrong(4))
    },
    row("LG-03", "the 5th wrong PIN: 429 for 5 minutes") {
        val p = phone()
        repeat(4) { p.login(MX_WRONG) }
        val a = p.login(MX_WRONG).isError(429, "locked_out", "محاولات خاطئة كثيرة. حاول بعد 5 دقائق.")
        assertThat(a.header("Retry-After")!!.toLong()).isIn(290L..300L)
    },
    row("LG-04", "the right PIN during the lockout: still 429") {
        val p = phone()
        repeat(5) { p.login(MX_WRONG) }
        p.login().isError(429, "locked_out")
        assertThat(p.session).isNull()
    },
    row("LG-05", "after the first lockout, 5 more wrong PINs: the second lasts 3 times longer") {
        val p = phone()
        repeat(5) { p.login(MX_WRONG) }
        clock.advanceMillis(5 * 60_000L + 1_000)
        // A new process: the server's brake starts from nothing, the phone's count is read back from the store.
        val (_, port) = restartedServer()
        p.port = port
        repeat(4) { p.login(MX_WRONG).isError(401, "wrong_pin") }
        val a = p.login(MX_WRONG).isError(429, "locked_out")
        assertThat(a.header("Retry-After")!!.toLong()).isIn(890L..900L)
    },
    row("LG-06", "the TV's count locked out: the phone logs in, and counts on its own") {
        lockOutTvPin()
        val p = phone()
        p.login().isOkWith()
        p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(4))
    },
    row("LG-07", "the phone locked out: the TV's PIN pad still opens") {
        val p = phone()
        repeat(5) { p.login(MX_WRONG) }
        assertThat(parentPin.verify(MX_PIN)).isEqualTo(PinResult.Success)
    },
    row("LG-08", "the phone's lockout survives an app restart") {
        val p = phone()
        repeat(5) { p.login(MX_WRONG) }
        val (_, port) = restartedServer()
        p.port = port
        p.login().isError(429, "locked_out")
    },
    row("LG-09", "3 failures and 1 minute in the PIN brake settings: the phone follows them") {
        number(BehaviorKeys.PIN_MAX_FAILURES, 3)
        number(BehaviorKeys.PIN_FIRST_LOCKOUT_MINUTES, 1)
        val p = phone()
        p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(2))
        p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(1))
        val a = p.login(MX_WRONG).isError(429, "locked_out")
        assertThat(a.header("Retry-After")!!.toLong()).isIn(50L..60L)
    },
    row("LG-10", "8 failures in the PIN brake settings: 7 wrong PINs count down, no 429 before the 8th") {
        number(BehaviorKeys.PIN_MAX_FAILURES, 8)
        val p = phone()
        for (left in 7 downTo 1) p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(left))
        p.login(MX_WRONG).isError(429, "locked_out")
    },
    row("LG-11", "wrong PINs of switches and logins add up") {
        child()
        val p = phone()
        repeat(2) { p.switchTo("adult", MX_WRONG).isRefusedWith() }
        p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(2))
        p.login(MX_WRONG).isError(401, "wrong_pin", ServerTexts.wrongPin(1))
        p.login(MX_WRONG).isError(429, "locked_out")
    },
    row("LG-12", "a new pairing token forgets both counts: the right PIN works at once") {
        val p = phone()
        repeat(5) { p.login(MX_WRONG) }
        p.token = runtime.regenerateToken()
        p.login().isOkWith()
    },
    row("LG-13", "no parent PIN stored (defensive): 403 with the reason") {
        policyStore.edit { it.remove(PolicyStore.PARENT_PIN_HASH) }
        phone().login().isError(403, "rejected", texts.get(R.string.remote_pin_not_set))
    },
    row("LG-14", "logout ends that session") {
        val p = phone().loggedIn()
        val old = p.session
        p.post("/api/parent/logout").isOkWith(ServerTexts.LOGGED_OUT)
        p.session = old
        p.get("/api/parent/settings").isError(403, "parent_session")
    },
    row("LG-15", "logout without a session, or with an unknown one: still 200") {
        phone().post("/api/parent/logout").isOkWith(ServerTexts.LOGGED_OUT)
        phone().apply { session = "nobody" }.post("/api/parent/logout").isOkWith(ServerTexts.LOGGED_OUT)
    },
)

// ---- Q: the daily quota -------------------------------------------------------------------------------------------------

private fun quotaRows() = listOf(
    row("Q-01", "a child's quota to 30: stored, said, the mark moves") {
        val p = phone().loggedIn()
        val before = p.status().long("rev")
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":30}""").isOkWith(texts.get(R.string.remote_quota_saved, "سارة", texts.minutes(30)))
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(30)
        waitFor("rev moves") { p.status().long("rev") != before }
    },
    row("Q-02", "no daily limit") {
        phone().loggedIn().post("/api/parent/quota", """{"profileId":"kid","minutes":null}""").isOkWith(texts.get(R.string.remote_quota_unlimited, "سارة"))
        assertThat(profiles.get("kid")!!.dailyQuotaMinutes).isNull()
    },
    row("Q-03", "a value the TV does not offer (75, 0, 1440): refused, nothing stored") {
        val p = phone().loggedIn()
        for (minutes in listOf(75, 0, 1440, 5)) {
            p.post("/api/parent/quota", """{"profileId":"kid","minutes":$minutes}""").isNotAccepted()
            assertWithMessage("$minutes").that(profiles.get("kid")!!.dailyQuotaMinutes).isEqualTo(60)
        }
        // What the TV offers is what the phone is offered.
        val choices = p.profilesPage().getValue("quotaChoices").jsonArray.map { it.takeUnless { e -> e is JsonNull }?.jsonPrimitive?.intOrNull }
        assertThat(choices).containsExactly(null, 15, 30, 45, 60, 90, 120, 180, 240).inOrder()
        for (minutes in listOf(15, 45, 240)) p.post("/api/parent/quota", """{"profileId":"kid","minutes":$minutes}""").isOkWith()
    },
    row("Q-04", "an unknown profile") {
        phone().loggedIn().post("/api/parent/quota", """{"profileId":"nobody","minutes":30}""").isRefusedWith()
    },
    row("Q-05", "the current child at 40 minutes, quota to 30: time up at once") {
        child()
        used(40 * 60)
        val p = phone().loggedIn()
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":30}""").isOkWith()
        waitFor("time up") { p.status().str("sessionState") == "TIME_UP" }
    },
    row("Q-06", "the current child in time up, no limit: open at once, minutes to bedtime") {
        child()
        used(3600)
        val p = phone().loggedIn()
        p.post("/api/parent/quota", """{"profileId":"kid","minutes":null}""").isOkWith()
        waitFor("open") { p.status().str("sessionState") == "OK" }
        assertThat(p.status().int("timeLeftMinutes")).isEqualTo(600)
    },
    row("Q-07", "the adult gets a limit") {
        val p = phone().loggedIn()
        p.post("/api/parent/quota", """{"profileId":"adult","minutes":120}""").isOkWith()
        waitFor("limited") { p.status().int("timeLeftMinutes") == 120 }
    },
    row("Q-08", "under a locked video: the quota changes, the lock does not") {
        child()
        lockedVideo(INSIDE)
        phone().loggedIn().post("/api/parent/quota", """{"profileId":"kid","minutes":45}""").isOkWith()
        assertThat(lock.current()!!.videoId).isEqualTo(INSIDE)
    },
)

// ---- UT: opening time ---------------------------------------------------------------------------------------------------

private suspend fun MxPhone.unlockTime(minutes: Int?) = post("/api/parent/unlock-time", """{"extraMinutes":${minutes ?: "null"}}""")

private fun timeRows() = listOf(
    row("UT-01", "time up, +15: open for 15 minutes; the guard keeps only the child") {
        child()
        used(3600)
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith(texts.get(R.string.remote_time_added, "سارة", texts.minutes(15)))
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(15)
        assertThat(guard()).isEqualTo(GuardDemand(enforce = true, child = true))
    },
    row("UT-02", "time up, rest of today: no quota today, bedtime still counts") {
        child()
        used(3600)
        val p = phone().loggedIn()
        p.unlockTime(null).isOkWith(texts.get(R.string.remote_time_rest_of_today, "سارة"))
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(600)
    },
    row("UT-03", "outside the schedule, +30: open for 30 minutes") {
        child()
        at("2026-09-28T23:00")
        val p = phone().loggedIn()
        p.unlockTime(30).isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(30)
    },
    row("UT-04", "outside the schedule, rest of today: open until midnight") {
        child()
        at("2026-09-28T23:00")
        val p = phone().loggedIn()
        p.unlockTime(null).isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(60)
    },
    row("UT-05", "20 minutes left, +15: 35") {
        child()
        used(40 * 60)
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith()
        assertThat(p.status().int("timeLeftMinutes")).isEqualTo(35)
    },
    row("UT-06", "WARNING with 4 left, +15: 19, warning gone") {
        child()
        used(56 * 60)
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(19)
    },
    row("UT-07", "a profile without any limit: said so, nothing stored") {
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith(texts.get(R.string.remote_time_no_limit, "الكبار"))
        p.unlockTime(null).isOkWith(texts.get(R.string.remote_time_no_limit, "الكبار"))
        assertThat(overrides.current().quota).isEmpty()
        assertThat(overrides.current().schedule).isEmpty()
    },
    row("UT-08", "time up at 21:58 (bedtime in 2 minutes), +15: 15 minutes (quota and bedtime)") {
        child()
        at("2026-09-28T21:58")
        used(3600)
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.int("timeLeftMinutes")).isEqualTo(15)
    },
    row("UT-09", "time up over a locked video, +15: open, the video still locked") {
        child()
        lockedVideo(INSIDE)
        used(3600)
        releasePlayer()
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("OK")
        assertThat(s.bool("videoLocked")).isTrue()
    },
    row("UT-10", "the adult with a quota, time up, +15") {
        quota("adult", 30)
        used(30 * 60, "adult")
        val p = phone().loggedIn()
        p.unlockTime(15).isOkWith(texts.get(R.string.remote_time_added, "الكبار", texts.minutes(15)))
        assertThat(p.status().int("timeLeftMinutes")).isEqualTo(15)
    },
)

// ---- UV: ending a locked video -------------------------------------------------------------------------------------------

private fun unlockRows() = listOf(
    row("UV-01", "nothing locked") {
        phone().loggedIn().post("/api/parent/unlock-video").isRefusedWith(texts.get(R.string.remote_video_not_locked))
    },
    row("UV-02", "a child's lock: ended, no surrender, STOP allowed") {
        child()
        lockedVideo(INSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/unlock-video").isOkWith(texts.get(R.string.remote_video_unlocked))
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders("kid", 0)).isEmpty()
        assertThat(p.status().bool("videoLocked")).isFalse()
        p.control("STOP").isOkWith()
    },
    row("UV-03", "the adult's lock: the surrender stored first, then ended") {
        lockedVideo(OUTSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/unlock-video").isOkWith(texts.get(R.string.remote_video_unlocked_adult))
        assertThat(lock.current()).isNull()
        assertThat(usage.surrenders("adult", 0)).hasSize(1)
        val stats = p.get("/api/parent/stats").arr.map { it.jsonObject }.first { it.str("profileId") == "adult" }
        assertThat(stats.int("surrenders")).isEqualTo(1)
    },
    row("UV-04", "the adult's surrender cannot be stored: the lock stays") {
        lockedVideo(OUTSIDE)
        failSurrenders = true
        phone().loggedIn().post("/api/parent/unlock-video").isRefusedWith(texts.get(R.string.remote_video_unlock_failed))
        assertThat(lock.current()!!.videoId).isEqualTo(OUTSIDE)
    },
    row("UV-05", "the adult's escape off: the phone still ends the lock") {
        adultEscape(false)
        lockedVideo(OUTSIDE)
        phone().loggedIn().post("/api/parent/unlock-video").isOkWith()
        assertThat(lock.current()).isNull()
    },
    row("UV-06", "a child's lock during time up: ended, time up stays") {
        child()
        lockedVideo(INSIDE)
        used(3600)
        releasePlayer()
        val p = phone().loggedIn()
        p.post("/api/parent/unlock-video").isOkWith()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("TIME_UP")
        assertThat(s.bool("videoLocked")).isFalse()
    },
)

// ---- AL: a child's allowlist ---------------------------------------------------------------------------------------------

private suspend fun MxPhone.allow(link: String, profile: String = "kid") =
    post("/api/parent/allowlist/add", """{"profileId":"$profile","link":${JsonPrimitive(link)}}""")

private fun allowlistRows() = listOf(
    row("AL-01", "a child's lists") {
        val a = phone().loggedIn().get("/api/parent/allowlist?profileId=kid")
        assertThat(a.code).isEqualTo(200)
        assertThat(a.obj.getValue("channels").jsonArray.map { it.jsonObject.str("id") }).containsExactly(ALLOWED_CHANNEL)
        assertThat(a.obj.getValue("playlists").jsonArray.map { it.jsonObject.str("id") }).containsExactly(ALLOWED_PLAYLIST)
    },
    row("AL-02", "the adult's or an unknown profile's list: refused") {
        val p = phone().loggedIn()
        p.get("/api/parent/allowlist?profileId=adult").isError(403, "rejected", texts.get(R.string.remote_allowlist_child_only))
        p.get("/api/parent/allowlist?profileId=nobody").isError(403, "rejected", texts.get(R.string.remote_profile_not_found))
    },
    row("AL-03", "a new channel for the current child: shown at once, its videos play from a link") {
        source.videos["newvideo001"] = clip("newvideo001", channelId = NEW_CHANNEL, title = "New one")
        child()
        val p = phone().loggedIn()
        p.play(watch("newvideo001")).isRefusedWith(texts.deny(DenyReason.NOT_IN_ALLOWLIST))
        p.allow(channelLink(NEW_CHANNEL)).isOkWith(texts.get(R.string.remote_allowlist_added, "قناة جديدة", "سارة"))
        waitFor("the child's rules follow") { gate.filter.value.profile?.allowlist?.hasChannel(NEW_CHANNEL) == true }
        p.play(watch("newvideo001")).isOkWith()
    },
    row("AL-04", "a channel by @handle") {
        phone().loggedIn().allow("https://www.youtube.com/@newone").isOkWith(texts.get(R.string.remote_allowlist_added, "قناة جديدة", "سارة"))
        assertThat(profiles.get("kid")!!.allowlist.hasChannel(NEW_CHANNEL)).isTrue()
    },
    row("AL-05", "a channel already there") {
        phone().loggedIn().allow(channelLink(ALLOWED_CHANNEL)).isOkWith(texts.get(R.string.remote_allowlist_exists, "قناة مسموحة", "سارة"))
        assertThat(profiles.get("kid")!!.allowlist.channels).hasSize(1)
    },
    row("AL-06", "a playlist link, and a video link with list=") {
        val p = phone().loggedIn()
        p.allow(playlistLink(NEW_PLAYLIST)).isOkWith(texts.get(R.string.remote_allowlist_added, "قائمة جديدة", "سارة"))
        p.allow(watch(INSIDE, list = OTHER_PLAYLIST)).isOkWith(texts.get(R.string.remote_allowlist_added, "قائمة أخرى", "سارة"))
        assertThat(profiles.get("kid")!!.allowlist.playlists.map { it.playlistId }).containsAtLeast(NEW_PLAYLIST, OTHER_PLAYLIST)
    },
    row("AL-07", "a plain video link or not YouTube: refused") {
        val p = phone().loggedIn()
        p.allow(watch(INSIDE)).isRefusedWith(texts.get(R.string.remote_allowlist_bad_link))
        p.allow("hello").isRefusedWith(texts.get(R.string.remote_allowlist_bad_link))
    },
    row("AL-08", "to the adult: refused") {
        phone().loggedIn().allow(channelLink(NEW_CHANNEL), "adult").isRefusedWith(texts.get(R.string.remote_allowlist_child_only))
    },
    row("AL-09", "a channel YouTube cannot resolve") {
        val e = SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "UnresolvedChannel", "@ghost", null)
        phone().loggedIn().allow("https://www.youtube.com/@ghost").isRefusedWith(texts.sourceError(e))
    },
    row("AL-10", "removing the current child's channel: its videos are refused at once") {
        child()
        val p = phone().loggedIn()
        p.post("/api/parent/allowlist/remove", """{"profileId":"kid","id":"$ALLOWED_CHANNEL"}""")
            .isOkWith(texts.get(R.string.remote_allowlist_removed, "قناة مسموحة", "سارة"))
        waitFor("the child's rules follow") { gate.filter.value.profile?.allowlist?.hasChannel(ALLOWED_CHANNEL) == false }
        p.play(watch(INSIDE)).isRefusedWith(texts.deny(DenyReason.NOT_IN_ALLOWLIST))
    },
    row("AL-11", "removing what is not there") {
        phone().loggedIn().post("/api/parent/allowlist/remove", """{"profileId":"kid","id":"UCnothere"}""")
            .isRefusedWith(texts.get(R.string.remote_allowlist_not_there))
    },
    row("AL-12", "removing the channel of the locked video: the video goes on locked") {
        child()
        lockedVideo(INSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/allowlist/remove", """{"profileId":"kid","id":"$ALLOWED_CHANNEL"}""").isOkWith()
        assertThat(lock.current()!!.videoId).isEqualTo(INSIDE)
        p.play(watch(INSIDE2)).isRefusedWith(texts.deny(DenyReason.LOCKED))
    },
)

// ---- SU / TK: subscriptions and Takeout ----------------------------------------------------------------------------------

private fun subscriptionRows() = listOf(
    row("SU-01", "the list") {
        subscriptions.add(ChannelItem(OTHER_CHANNEL, "قناة أخرى", "https://yt3.example/a.jpg", 1))
        val a = phone().loggedIn().get("/api/parent/subscriptions")
        assertThat(a.code).isEqualTo(200)
        assertThat(a.arr.map { it.jsonObject.str("channelId") }).containsExactly(OTHER_CHANNEL)
    },
    row("SU-02", "add a channel link: subscribed, synced, the mark moves") {
        val p = phone().loggedIn()
        val before = p.status().long("rev")
        p.post("/api/parent/subscriptions/add", """{"link":"${channelLink(NEW_CHANNEL)}"}""").isOkWith(texts.get(R.string.remote_subs_added, "قناة جديدة"))
        assertThat(syncs).isAtLeast(1)
        waitFor("rev moves") { p.status().long("rev") != before }
    },
    row("SU-03", "add it again") {
        val p = phone().loggedIn()
        p.post("/api/parent/subscriptions/add", """{"link":"${channelLink(NEW_CHANNEL)}"}""").isOkWith()
        p.post("/api/parent/subscriptions/add", """{"link":"${channelLink(NEW_CHANNEL)}"}""").isOkWith(texts.get(R.string.remote_subs_exists, "قناة جديدة"))
    },
    row("SU-04", "a video or not-YouTube link: refused") {
        val p = phone().loggedIn()
        p.post("/api/parent/subscriptions/add", """{"link":"${watch(INSIDE)}"}""").isRefusedWith(texts.get(R.string.remote_subs_not_channel))
        p.post("/api/parent/subscriptions/add", """{"link":"hello"}""").isRefusedWith(texts.get(R.string.remote_subs_not_channel))
    },
    row("SU-05", "remove a subscribed channel") {
        subscriptions.add(ChannelItem(OTHER_CHANNEL, "قناة أخرى", null, 1))
        phone().loggedIn().post("/api/parent/subscriptions/remove", """{"channelId":"$OTHER_CHANNEL"}""")
            .isOkWith(texts.get(R.string.remote_subs_removed, "قناة أخرى"))
        assertThat(subscriptions.observe().first()).isEmpty()
    },
    row("SU-06", "remove one not subscribed") {
        phone().loggedIn().post("/api/parent/subscriptions/remove", """{"channelId":"$OTHER_CHANNEL"}""")
            .isRefusedWith(texts.get(R.string.remote_subs_not_subscribed))
    },
    row("SU-07", "under a locked video, in a child profile, in time up: still done") {
        child()
        lockedVideo(INSIDE)
        used(3600)
        val p = phone().loggedIn()
        p.post("/api/parent/subscriptions/add", """{"link":"${channelLink(NEW_CHANNEL)}"}""").isOkWith()
        p.post("/api/parent/subscriptions/remove", """{"channelId":"$NEW_CHANNEL"}""").isOkWith()
    },
)

private suspend fun MxPhone.takeout(bytes: ByteArray, fileName: String? = "subscriptions.csv", contentType: String = "text/csv") =
    call("POST", "/api/parent/takeout", bytes, contentType, headers = fileName?.let { mapOf("X-File-Name" to it) } ?: emptyMap())

private fun takeoutRows() = listOf(
    row("TK-01", "a raw CSV with 3 new channels") {
        val csv = csvOf(UC_A to "أ", UC_B to "ب", UC_C to "ج").toByteArray()
        phone().loggedIn().takeout(csv).isOkWith(texts.get(R.string.remote_takeout_added, texts.channels(3)))
        assertThat(subscriptions.observe().first()).hasSize(3)
        assertThat(syncs).isAtLeast(1)
    },
    row("TK-02", "the same CSV as multipart") {
        val (body, type) = multipart("subscriptions.csv", csvOf(UC_A to "أ", UC_B to "ب", UC_C to "ج").toByteArray())
        phone().loggedIn().call("POST", "/api/parent/takeout", body, type).isOkWith(texts.get(R.string.remote_takeout_added, texts.channels(3)))
    },
    row("TK-03", "the whole Takeout zip") {
        val zip = zipOf(
            "Takeout/YouTube and YouTube Music/history/watch-history.html" to "<html></html>".toByteArray(),
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csvOf(UC_A to "أ", UC_B to "ب").toByteArray(),
        )
        phone().loggedIn().takeout(zip, "takeout-20260928.zip", "application/zip").isOkWith(texts.get(R.string.remote_takeout_added, texts.channels(2)))
    },
    row("TK-04", "everything already there") {
        val p = phone().loggedIn()
        val csv = csvOf(UC_A to "أ", UC_B to "ب", UC_C to "ج").toByteArray()
        p.takeout(csv).isOkWith()
        p.takeout(csv).isOkWith(texts.get(R.string.remote_takeout_all_present, texts.channels(3)))
    },
    row("TK-05", "some new, some there") {
        val p = phone().loggedIn()
        p.takeout(csvOf(UC_A to "أ", UC_B to "ب", UC_C to "ج").toByteArray()).isOkWith()
        p.takeout(csvOf(UC_A to "أ", UC_B to "ب", UC_C to "ج", UC_D to "د").toByteArray())
            .isOkWith(texts.get(R.string.remote_takeout_added_some_present, texts.channels(1), texts.channels(3)))
    },
    row("TK-06", "a CSV without a channel") {
        phone().loggedIn().takeout("Channel Id,Channel Url,Channel Title\n".toByteArray()).isRefusedWith(texts.get(R.string.import_error_no_channels))
        assertThat(subscriptions.observe().first()).isEmpty()
    },
    row("TK-07", "a zip without the CSV; a broken zip") {
        val p = phone().loggedIn()
        p.takeout(zipOf("Takeout/readme.txt" to "hi".toByteArray()), "takeout.zip", "application/zip").isError(400, "bad_request", ServerTexts.TAKEOUT_NOT_FOUND)
        p.takeout("not a zip".toByteArray(), "takeout.zip", "application/zip").isError(400, "bad_request", ServerTexts.TAKEOUT_BROKEN)
    },
    row("TK-08", "a picture") {
        phone().loggedIn().takeout(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "photo.png", "image/png").isError(415, "unsupported", ServerTexts.TAKEOUT_TYPE)
    },
)

// ---- BK: backup -------------------------------------------------------------------------------------------------------------

private fun backupRows() = listOf(
    row("BK-01", "the export: an attachment, no-store, everything but secrets and caches") {
        number(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        playback.setMaxQuality(720)
        val a = phone().loggedIn().get("/api/parent/backup")
        assertThat(a.code).isEqualTo(200)
        assertThat(a.header("Content-Disposition")).contains("shasha-backup-")
        assertThat(a.header("Content-Disposition")).contains(".json")
        assertThat(a.header("Cache-Control")).contains("no-store")
        val json = a.obj
        assertThat(json.str("app")).isEqualTo("shasha")
        assertThat(json["profiles"]!!.jsonArray).hasSize(2)
        assertThat(json.str("parentPinHash")).isNotEmpty()
        assertThat(a.body).doesNotContain(MX_PIN)
        assertThat(a.body).doesNotContain(token)
        assertThat(a.body).contains("seek_step_seconds")
        assertThat(a.body).contains("max_quality")
    },
    row("BK-02", "export, change, restore: everything back") {
        val p = phone().loggedIn()
        val exported = p.get("/api/parent/backup").body
        profiles.update("kid") { it.copy(name = "تغيّر") }
        behavior.setSeriousMode(false)
        val before = p.status().long("rev")
        p.call("POST", "/api/parent/backup", exported.toByteArray()).isOkWith()
        assertThat(profiles.get("kid")!!.name).isEqualTo("سارة")
        assertThat(behavior.current().seriousMode).isTrue()
        waitFor("rev moves") { p.status().long("rev") != before }
    },
    row("BK-03", "a restore under a locked video: refused, nothing replaced") {
        val p = phone().loggedIn()
        val exported = p.get("/api/parent/backup").body
        profiles.update("kid") { it.copy(name = "تغيّر") }
        child()
        lockedVideo(INSIDE)
        p.call("POST", "/api/parent/backup", exported.toByteArray()).isRefusedWith(texts.get(R.string.remote_backup_locked))
        assertThat(profiles.get("kid")!!.name).isEqualTo("تغيّر")
    },
    row("BK-04", "not a backup") {
        phone().loggedIn().call("POST", "/api/parent/backup", """{"hello":"world"}""".toByteArray()).isRefusedWith(texts.get(R.string.backup_error_not_backup))
    },
    row("BK-05", "a backup of a newer format") {
        val p = phone().loggedIn()
        val exported = Json.parseToJsonElement(p.get("/api/parent/backup").body).jsonObject
        val newer = JsonObject(exported + ("format" to JsonPrimitive(99)))
        p.call("POST", "/api/parent/backup", newer.toString().toByteArray()).isRefusedWith(texts.get(R.string.backup_error_newer))
    },
    row("BK-06", "a backup with another PIN: the PIN replaced, every session ends") {
        val p = phone().loggedIn()
        val b = phone("B").loggedIn()
        val exported = p.get("/api/parent/backup").body
        parentPin.change(MX_PIN, "8642")
        waitFor("revoked by the PIN change") { p.get("/api/parent/settings").code == 403 }
        p.login("8642").isOkWith()
        b.login("8642").isOkWith()
        p.call("POST", "/api/parent/backup", exported.toByteArray()).isOkWith()
        p.get("/api/parent/settings").isError(403, "parent_session")
        b.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(parentPin.verify(MX_PIN)).isEqualTo(PinResult.Success)
    },
    row("BK-07", "the server stops in the middle of a restore: the restore still finishes") {
        val p = phone().loggedIn()
        val exported = p.get("/api/parent/backup").body
        profiles.update("kid") { it.copy(name = "تغيّر") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringRestore = { entered.complete(Unit); release.await() }
        val upload = scope.async(Dispatchers.IO) { runCatching { p.call("POST", "/api/parent/backup", exported.toByteArray()) } }
        entered.await()
        runtime.stop() // the TV's address changed: the server stops and cancels the calls still running
        release.complete(Unit)
        waitFor("the restore finished", 10_000) { restoresFinished == 1 }
        assertThat(profiles.get("kid")!!.name).isEqualTo("سارة")
        upload.await()
    },
    row("BK-08", "a backup without the current child: the TV falls back to the owner") {
        val p = phone().loggedIn()
        val exported = p.get("/api/parent/backup").body
        addChild2()
        child("kid2")
        p.call("POST", "/api/parent/backup", exported.toByteArray()).isOkWith()
        waitFor("back on the owner") { current().id == "adult" }
        assertThat(p.status().str("profileKind")).isEqualTo("ADULT")
    },
)

// ---- SET: every TV setting from the phone ---------------------------------------------------------------------------------

/** One setting: a valid change (and what the TV then has), an invalid one (refused, nothing stored), its default. */
private class SettingCase(
    val id: String,
    val key: String,
    val default: String,
    val valid: String,
    val invalid: List<String>,
    val prepare: suspend MxTv.() -> Unit = {},
    val stored: suspend MxTv.() -> String,
    val after: suspend MxTv.(MxPhone) -> Unit = {},
)

private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> JsonPrimitive(value).toString()
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> value.toString()
}

private val settingCases = listOf(
    SettingCase("SET-10", "serious_mode", "true", "false", listOf("\"yes\"", "1"),
        prepare = { lockedVideo(OUTSIDE) },
        stored = { behavior.current().seriousMode.toString() },
        after = { phone ->
            // Off: the lock that held a video ends at once, no surrender; nothing locks any more.
            waitFor("the lock ends") { lock.current() == null }
            assertThat(usage.surrenders("adult", 0)).isEmpty()
            assertThat(phone.status().bool("seriousMode")).isFalse()
            startVideo(PL_VIDEO_1)
            assertThat(lock.current()).isNull()
        }),
    SettingCase("SET-11", "adult_escape", "true", "false", listOf("1", "\"no\""), stored = { behavior.current().adultEscape.toString() },
        after = { assertThat(engine.config().escape).isNull() }),
    SettingCase("SET-12", "escape_hold_seconds", "10", "20", listOf("7", "\"20\"", "20.5"), stored = { behavior.current().escapeHoldSeconds.toString() },
        after = { assertThat(engine.config().escape!!.holdMillis).isEqualTo(20_000L) }),
    SettingCase("SET-13", "phrase_mode", "\"RANDOM\"", "\"CUSTOM\"", listOf("\"OTHER\"", "1"),
        prepare = {
            // Without a phrase of one's own, CUSTOM is refused with the reason, and offered dimmed with it.
            val phone = phone().loggedIn()
            phone.setting("phrase_mode", "\"CUSTOM\"").isRefusedWith(texts.get(R.string.phone_setting_custom_first))
            val choice = phone.settingsPage().setting("phrase_mode").getValue("choices").jsonArray.map { it.jsonObject }.first { it.str("value") == "CUSTOM" }
            assertThat(choice.str("disabledReason")).isEqualTo(texts.get(R.string.phone_setting_custom_first))
            behavior.setCustomPhrase("بسم الله")
            behavior.setPhraseMode(com.ahmadnayfeh.shasha.data.PhraseMode.RANDOM)
        },
        stored = { behavior.current().phraseMode.name.let { "\"$it\"" } },
        after = { assertThat(engine.config().exitPhrases).containsExactly("بسم الله") }),
    SettingCase("SET-14", "phrases", "default", json(listOf("صباح الخير", "طاب يومك")), listOf("[]", json(listOf("hello world")), "\"x\""),
        stored = { json(behavior.current().phrases) }),
    SettingCase("SET-15", "custom_phrase", "\"\"", "\"اللهم بارك\"", listOf("\"hello\"", json("ب".repeat(31))),
        stored = { "\"" + behavior.current().customPhrase + "\"" },
        after = { assertThat(behavior.current().phraseMode.name).isEqualTo("CUSTOM") }),
    SettingCase("SET-16", "skip_allowance_seconds", "30", "60", listOf("45", "-1"), stored = { behavior.current().skipAllowanceSeconds.toString() }),
    SettingCase("SET-17", "played_percent", "50", "75", listOf("60", "101"), stored = { behavior.current().playedPercent.toString() }),
    SettingCase("SET-18", "warning_minutes", "5", "0", listOf("4", "16"), stored = { behavior.current().warningMinutes.toString() },
        after = { assertThat(engine.config().warningSeconds).isEqualTo(0L) }),
    SettingCase("SET-19", "warning_banner_seconds", "12", "20", listOf("15"), stored = { behavior.current().warningBannerSeconds.toString() }),
    SettingCase("SET-20", "extra_minutes", "[15,30,60]", "[10,20]", listOf("[]", "[5,10,15,20,30]", "[7]", "[15,7]"),
        stored = { json(behavior.current().extraMinutes) }),
    SettingCase("SET-21", "default_speed", "1.0", "1.5", listOf("1.3", "0"), stored = { behavior.current().defaultSpeed.toString() }),
    SettingCase("SET-22", "seek_step_seconds", "10", "30", listOf("25"), stored = { behavior.current().seekStepSeconds.toString() }),
    SettingCase("SET-23", "controls_hide_seconds", "3", "5", listOf("4"), stored = { behavior.current().controlsHideSeconds.toString() }),
    SettingCase("SET-24", "up_next_seconds", "5", "0", listOf("7"), stored = { behavior.current().upNextSeconds.toString() }),
    SettingCase("SET-25", "kids_home_seconds", "10", "30", listOf("20"), stored = { behavior.current().kidsHomeSeconds.toString() }),
    SettingCase("SET-26", "sponsor_undo_seconds", "5", "8", listOf("6"), stored = { behavior.current().sponsorUndoSeconds.toString() }),
    SettingCase("SET-27", "sponsor_notice_seconds", "3", "5", listOf("4"), stored = { behavior.current().sponsorNoticeSeconds.toString() }),
    SettingCase("SET-28", "shorts_max_seconds", "180", "60", listOf("100"), stored = { behavior.current().shortsMaxSeconds.toString() },
        after = { phone ->
            // A 2-minute allowlisted video is no longer a short: the child's link plays.
            child()
            phone.play(watch(SHORT_INSIDE)).isOkWith()
        }),
    SettingCase("SET-29", "voice_language", "\"ar-SA\"", "\"en-US\"", listOf("\"fr-FR\""), stored = { "\"" + behavior.current().voiceLanguage + "\"" }),
    SettingCase("SET-30", "pin_max_failures", "5", "3", listOf("7"), stored = { behavior.current().pinMaxFailures.toString() },
        after = { assertThat(behavior.pinLockout().maxFailures).isEqualTo(3) }),
    SettingCase("SET-31", "pin_first_lockout_minutes", "5", "1", listOf("3"), stored = { behavior.current().pinFirstLockoutMinutes.toString() }),
    SettingCase("SET-32", "parent_unlock_minutes", "5", "10", listOf("4"), stored = { behavior.current().parentUnlockMinutes.toString() }),
    SettingCase("SET-33", "relock_seconds", "15", "60", listOf("45"), stored = { behavior.current().relockSeconds.toString() }),
    SettingCase("SET-34", "adult_entry_pin", "true", "false", listOf("\"off\""), stored = { behavior.current().adultEntryPin.toString() },
        after = { phone ->
            assertThat(engine.config().adultEntryNeedsPin).isFalse()
            assertThat(phone.settingsPage().setting("adult_entry_pin").str("warning")).isNotEmpty()
        }),
    SettingCase("SET-35", "sponsor_categories", json(listOf("sponsor", "selfpromo", "interaction", "intro", "outro")), "[]",
        listOf(json(listOf("nope")), "\"sponsor\""), stored = { json(com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory.entries.filter { it in sponsor.categories.first() }.map { it.apiKey }) }),
    SettingCase("SET-36", "max_quality", "1080", "720", listOf("360", "\"720\""), stored = { playback.current().toString() }),
)

private fun sameJson(a: String, b: String): Boolean {
    val x = Json.parseToJsonElement(a)
    val y = Json.parseToJsonElement(b)
    if (x is JsonArray && y is JsonArray) return x.size == y.size && x.all { e -> y.any { PhoneSettings.sameValue(e, it) } }
    return PhoneSettings.sameValue(x, y)
}

private fun settingsRows(): List<MxRow> = listOf(
    row("SET-01", "the page: every group, each setting with the TV's value, default and choices") {
        val page = phone().loggedIn().settingsPage()
        val groups = page.getValue("groups").jsonArray.map { it.jsonObject.str("id") }
        assertThat(groups).containsAtLeast("serious", "playback", "pin", "sponsorblock", "quality", "remote")
        val keys = page.getValue("groups").jsonArray.flatMap { it.jsonObject.getValue("items").jsonArray }.map { it.jsonObject.str("key") }
        // Every BehaviorKeys setting, SponsorBlock, the quality and the port.
        assertThat(keys).containsAtLeastElementsIn(BehaviorKeys.ALL.keys + listOf("sponsor_categories", "max_quality", "remote_port"))
        val hold = page.setting("escape_hold_seconds")
        assertThat(hold.getValue("choices").jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.intOrNull })
            .containsExactlyElementsIn(BehaviorKeys.ESCAPE_HOLD_CHOICES).inOrder()
    },
    row("SET-02", "a change: stored in the TV's file, the whole page back, the mark moves") {
        val p = phone().loggedIn()
        val before = p.status().long("rev")
        val a = p.setting("seek_step_seconds", "30").isOkWith()
        assertThat(a.message).startsWith("حُفظ")
        assertThat(behavior.current().seekStepSeconds).isEqualTo(30)
        assertThat(a.obj.setting("seek_step_seconds").int("value")).isEqualTo(30)
        waitFor("rev moves") { p.status().long("rev") != before }
    },
    row("SET-03", "an unknown key") {
        val a = phone().loggedIn().setting("no_such_setting", "1").isRefusedWith(texts.get(R.string.phone_setting_unknown))
        assertThat(a.obj["settings"]).isNotNull()
    },
    row("SET-04", "changed meanwhile on the TV: the stale change is refused with the TV's value") {
        val p = phone().loggedIn()
        behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 60) // the TV's own Settings
        val a = p.setting("seek_step_seconds", "30", base = "10").isRefusedWith()
        assertThat(a.message).contains("على الشاشة أو من جوال آخر")
        assertThat(a.obj.setting("seek_step_seconds").int("value")).isEqualTo(60)
        assertThat(behavior.current().seekStepSeconds).isEqualTo(60)
    },
    row("SET-05", "a wrong JSON type: refused, never a server error") {
        val p = phone().loggedIn()
        p.setting("seek_step_seconds", "\"30\"").isRefusedWith()
        p.setting("serious_mode", "1").isRefusedWith()
        p.setting("extra_minutes", "15").isRefusedWith()
        assertThat(behavior.current()).isEqualTo(com.ahmadnayfeh.shasha.data.Behavior.DEFAULT)
    },
) + settingCases.map { case ->
    row(case.id, "${case.key}: the TV's choices, the TV's default, refused otherwise") {
        val p = phone().loggedIn()
        case.prepare(this)
        val page = p.settingsPage().setting(case.key)
        if (case.default != "default") {
            assertWithMessage("default of ${case.key}").that(sameJson(page.getValue("defaultValue").toString(), case.default)).isTrue()
        }
        for (bad in case.invalid) {
            val before = case.stored(this)
            val a = p.setting(case.key, bad)
            assertWithMessage("${case.key}=$bad: $a").that(a.code).isEqualTo(200)
            assertWithMessage("${case.key}=$bad: $a").that(a.ok).isFalse()
            assertWithMessage("${case.key}=$bad stored").that(case.stored(this)).isEqualTo(before)
        }
        val a = p.setting(case.key, case.valid).isOkWith()
        assertWithMessage("${case.key} stored").that(sameJson(case.stored(this), case.valid)).isTrue()
        assertWithMessage("${case.key} in the answer").that(sameJson(a.obj.setting(case.key).getValue("value").toString(), case.valid)).isTrue()
        case.after(this, p)
    }
} + listOf(
    row("SET-37", "remote_port: a free port moves the server (sessions kept); the same port; a taken or out-of-range port refused") {
        val p = phone().loggedIn()
        val old = port
        // Taken by another app on the TV: refused before anything moves.
        ServerSocket(0, 50, MX_LOOPBACK).use { taken ->
            p.setting("remote_port", taken.localPort.toString()).isRefusedWith(texts.get(R.string.phone_port_taken, taken.localPort.toString()))
        }
        p.setting("remote_port", "80").isRefusedWith()
        p.setting("remote_port", "70000").isRefusedWith()
        assertThat(port).isEqualTo(old)
        p.setting("remote_port", old.toString()).isOkWith()
        val target = MxTv.freePort()
        val a = p.setting("remote_port", target.toString()).isOkWith(texts.get(R.string.phone_port_moving, target.toString()))
        assertThat(a.obj.int("reconnectPort")).isEqualTo(target)
        waitFor("the server moved", 10_000) { (runtime.state.value as? RemoteState.Running)?.port == target }
        p.port = target
        assertThat(p.get("/api/parent/settings").code).isEqualTo(200)
        p.port = old
        assertThat(runCatching { p.get("/api/status").code }.getOrNull()).isNotEqualTo(200)
    },
)

// ---- RS: reset --------------------------------------------------------------------------------------------------------------

private fun resetRows() = listOf(
    row("RS-01", "the serious group only") {
        behavior.setAdultEscape(false)
        behavior.setNumber(BehaviorKeys.ESCAPE_HOLD_SECONDS, 20)
        behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        val a = phone().loggedIn().post("/api/parent/settings/reset", """{"group":"serious"}""")
            .isOkWith(texts.get(R.string.phone_reset_group, texts.get(R.string.hub_serious)))
        assertThat(behavior.current().adultEscape).isTrue()
        assertThat(behavior.current().escapeHoldSeconds).isEqualTo(10)
        assertThat(behavior.current().seekStepSeconds).isEqualTo(30)
        assertThat(a.obj["settings"]).isNotNull()
    },
    row("RS-02", "playback, pin, sponsorblock and quality: each only its own") {
        val p = phone().loggedIn()
        behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        behavior.setNumber(BehaviorKeys.PIN_MAX_FAILURES, 3)
        behavior.setAdultEntryPin(false)
        sponsor.setCategories(emptySet())
        playback.setMaxQuality(720)
        p.post("/api/parent/settings/reset", """{"group":"playback"}""").isOkWith()
        assertThat(behavior.current().seekStepSeconds).isEqualTo(10)
        assertThat(behavior.current().pinMaxFailures).isEqualTo(3)
        p.post("/api/parent/settings/reset", """{"group":"pin"}""").isOkWith()
        assertThat(behavior.current().pinMaxFailures).isEqualTo(5)
        assertThat(behavior.current().adultEntryPin).isTrue()
        assertThat(playback.current()).isEqualTo(720)
        p.post("/api/parent/settings/reset", """{"group":"sponsorblock"}""").isOkWith()
        assertThat(sponsor.categories.first()).isEqualTo(com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory.DEFAULTS)
        p.post("/api/parent/settings/reset", """{"group":"quality"}""").isOkWith()
        assertThat(playback.current()).isEqualTo(PlaybackSettings.DEFAULT_MAX_QUALITY)
    },
    row("RS-03", "the remote group: the port back to 8765, the server moves") {
        val p = phone().loggedIn()
        val defaultFree = runCatching { ServerSocket(RemoteSettings.DEFAULT_PORT, 50, MX_LOOPBACK).close(); true }.getOrDefault(false)
        val a = p.post("/api/parent/settings/reset", """{"group":"remote"}""").isOkWith()
        assertThat(remoteSettings.port.first()).isEqualTo(RemoteSettings.DEFAULT_PORT)
        assertThat(a.obj.int("reconnectPort")).isEqualTo(RemoteSettings.DEFAULT_PORT)
        if (defaultFree) waitFor("moved", 10_000) { (runtime.state.value as? RemoteState.Running)?.port == RemoteSettings.DEFAULT_PORT }
    },
    row("RS-04", "an unknown group") {
        phone().loggedIn().post("/api/parent/settings/reset", """{"group":"nope"}""").isRefusedWith(texts.get(R.string.phone_setting_unknown))
    },
    row("RS-05", "the global reset: every setting back, nothing else touched") {
        val p = phone().loggedIn()
        behavior.setSeriousMode(false)
        behavior.setAdultEntryPin(false)
        behavior.setNumber(BehaviorKeys.SEEK_STEP_SECONDS, 30)
        behavior.setExtraMinutes(listOf(10, 20))
        sponsor.setCategories(emptySet())
        playback.setMaxQuality(720)
        profiles.update("kid") { it.copy(dailyQuotaMinutes = 30) }
        lists.allowChannel("kid", ListedChannel(NEW_CHANNEL, "قناة جديدة"))
        lists.addKeyword(KEYWORD)
        subscriptions.add(ChannelItem(OTHER_CHANNEL, "قناة أخرى", null, 1))
        val profilesBefore = profiles.all()
        p.post("/api/parent/settings/reset", """{"group":null}""").isOkWith()
        assertThat(behavior.current()).isEqualTo(com.ahmadnayfeh.shasha.data.Behavior.DEFAULT)
        assertThat(sponsor.categories.first()).isEqualTo(com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory.DEFAULTS)
        assertThat(playback.current()).isEqualTo(PlaybackSettings.DEFAULT_MAX_QUALITY)
        assertThat(profiles.all()).isEqualTo(profilesBefore)
        assertThat(subscriptions.observe().first()).hasSize(1)
        assertThat(parentPin.verify(MX_PIN)).isEqualTo(PinResult.Success)
    },
    row("RS-06", "the global reset while an unlocked video plays: Serious Mode on, that video not locked, the next one is") {
        serious(false)
        startVideo(OUTSIDE)
        phone().loggedIn().post("/api/parent/settings/reset", """{"group":null}""").isOkWith()
        waitFor("Serious Mode back on") { behavior.values.value.seriousMode }
        assertThat(lock.current()).isNull()
        startVideo(PL_VIDEO_1)
        assertThat(lock.current()!!.videoId).isEqualTo(PL_VIDEO_1)
    },
)

// ---- PF: profiles from the phone -------------------------------------------------------------------------------------------

private fun parentProfileRows() = listOf(
    row("PF-01", "the page: details and the TV's own choices") {
        child()
        val page = phone().loggedIn().profilesPage()
        val list = page.getValue("profiles").jsonArray.map { it.jsonObject }
        val kid = list.first { it.str("id") == "kid" }
        assertThat(kid.int("dailyQuotaMinutes")).isEqualTo(60)
        assertThat(kid.int("scheduleFrom")).isEqualTo(420)
        assertThat(kid.int("scheduleUntil")).isEqualTo(1320)
        assertThat(kid.bool("hideShorts")).isTrue()
        assertThat(kid.bool("hideComments")).isTrue()
        assertThat(kid.bool("isCurrent")).isTrue()
        assertThat(kid.str("deleteBlockedReason")).isNotEmpty()
        assertThat(page.int("nameMaxLength")).isEqualTo(20)
        assertThat(page.getValue("childAvatars").jsonArray).isNotEmpty()
    },
    row("PF-02", "a new child with the TV's defaults and the household's blocks") {
        lists.addKeyword(KEYWORD)
        phone().loggedIn().post("/api/parent/profile/create", """{"name":"  ريم   الصغيرة ","avatar":"rocket"}""")
            .isOkWith(texts.get(R.string.phone_profile_created, "ريم الصغيرة"))
        val created = profiles.all().first { it.name == "ريم الصغيرة" }
        assertThat(created.isChild).isTrue()
        assertThat(created.dailyQuotaMinutes).isEqualTo(60)
        assertThat(created.schedule).isEqualTo(Profile.DEFAULT_CHILD_SCHEDULE)
        assertThat(created.hideShorts).isTrue()
        assertThat(created.hideComments).isTrue()
        assertThat(created.blocklist.titleKeywords).containsExactly(KEYWORD)
    },
    row("PF-03", "a name of 21 letters; an adult-only avatar") {
        val p = phone().loggedIn()
        p.post("/api/parent/profile/create", """{"name":"${"ب".repeat(21)}","avatar":"rocket"}""")
            .isRefusedWith(texts.get(R.string.phone_profile_name_invalid, "20"))
        p.post("/api/parent/profile/create", """{"name":"ليلى","avatar":"person"}""").isRefusedWith(texts.get(R.string.phone_profile_avatar_invalid))
        assertThat(profiles.all()).hasSize(2)
    },
    row("PF-04", "rename a child") {
        phone().loggedIn().post("/api/parent/profile/edit", """{"profileId":"kid","name":"سارة الكبيرة"}""")
            .isOkWith(texts.get(R.string.phone_profile_saved, "سارة الكبيرة"))
        assertThat(profiles.get("kid")!!.name).isEqualTo("سارة الكبيرة")
    },
    row("PF-05", "a child's avatar to an adult-only one: refused") {
        phone().loggedIn().post("/api/parent/profile/edit", """{"profileId":"kid","avatar":"person"}""").isRefusedWith(texts.get(R.string.phone_profile_avatar_invalid))
        assertThat(profiles.get("kid")!!.avatar).isEqualTo(Profile.DEFAULT_CHILD_AVATAR)
    },
    row("PF-06", "delete a child that is not on the TV") {
        addChild2()
        phone().loggedIn().post("/api/parent/profile/delete", """{"profileId":"kid2"}""").isOkWith(texts.get(R.string.phone_profile_deleted, "ريم"))
        assertThat(profiles.get("kid2")).isNull()
    },
    row("PF-07", "delete the profile on the TV: refused") {
        child()
        phone().loggedIn().post("/api/parent/profile/delete", """{"profileId":"kid"}""").isRefusedWith(texts.get(R.string.phone_profile_delete_current))
        assertThat(profiles.get("kid")).isNotNull()
    },
    row("PF-08", "delete the only adult: refused") {
        child()
        phone().loggedIn().post("/api/parent/profile/delete", """{"profileId":"adult"}""").isRefusedWith(texts.get(R.string.phone_profile_delete_last_adult))
        assertThat(profiles.get("adult")).isNotNull()
    },
    row("PF-09", "a child's shorts, the adult's comments") {
        val p = phone().loggedIn()
        p.post("/api/parent/profile/flags", """{"profileId":"kid","hideShorts":false}""").isOkWith(texts.get(R.string.phone_flags_saved, "سارة"))
        p.post("/api/parent/profile/flags", """{"profileId":"adult","hideComments":true}""").isOkWith()
        assertThat(profiles.get("kid")!!.hideShorts).isFalse()
        assertThat(profiles.get("adult")!!.hideComments).isTrue()
    },
    row("PF-10", "a child's comments shown: refused") {
        phone().loggedIn().post("/api/parent/profile/flags", """{"profileId":"kid","hideComments":false}""").isRefusedWith(texts.get(R.string.phone_comments_child))
        assertThat(profiles.get("kid")!!.hideComments).isTrue()
    },
    row("PF-11", "schedules: a window, the same time twice, none, until midnight") {
        val p = phone().loggedIn()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":1200}""").isOkWith()
        assertThat(profiles.get("kid")!!.schedule).isEqualTo(com.ahmadnayfeh.shasha.policy.Schedule(480, 1200))
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":600,"untilMinute":600}""").isOkWith(texts.get(R.string.phone_schedule_all_day, "سارة"))
        assertThat(profiles.get("kid")!!.schedule).isNull()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":480,"untilMinute":1200}""").isOkWith()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":null,"untilMinute":null}""").isOkWith(texts.get(R.string.phone_schedule_all_day, "سارة"))
        assertThat(profiles.get("kid")!!.schedule).isNull()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":1080,"untilMinute":0}""").isOkWith()
        assertThat(profiles.get("kid")!!.schedule).isEqualTo(com.ahmadnayfeh.shasha.policy.Schedule(1080, 1440))
    },
    row("PF-12", "the current child's window moved away from now: outside the schedule at once") {
        child()
        val p = phone().loggedIn()
        p.post("/api/parent/schedule", """{"profileId":"kid","fromMinute":780,"untilMinute":840}""").isOkWith()
        waitFor("outside") { p.status().str("sessionState") == "OUTSIDE_SCHEDULE" }
    },
    row("PF-13", "editing profiles under a locked video: done, the lock stays") {
        child()
        lockedVideo(INSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/profile/flags", """{"profileId":"kid","hideShorts":false}""").isOkWith()
        p.post("/api/parent/profile/edit", """{"profileId":"kid","name":"سارة٢"}""").isOkWith()
        p.post("/api/parent/profile/create", """{"name":"ليلى","avatar":"rocket"}""").isOkWith()
        assertThat(lock.current()!!.videoId).isEqualTo(INSIDE)
    },
)

// ---- BL / SS: the household blocklist, the stats ------------------------------------------------------------------------------

private fun blocklistRows() = listOf(
    row("BL-01", "the household's list") {
        lists.addKeyword(KEYWORD)
        blockChannel()
        val a = phone().loggedIn().get("/api/parent/blocklist")
        assertThat(a.obj.getValue("keywords").jsonArray.map { it.jsonPrimitive.content }).containsExactly(KEYWORD)
        assertThat(a.obj.getValue("channels").jsonArray.map { it.jsonObject.str("id") }).containsExactly(BLOCKED_CHANNEL)
    },
    row("BL-02", "a keyword blocked everywhere: a link with it is refused") {
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/add", """{"kind":"keyword","value":"$KEYWORD"}""").isOkWith(texts.get(R.string.phone_keyword_blocked, KEYWORD))
        profiles.all().forEach { assertThat(it.blocklist.titleKeywords).contains(KEYWORD) }
        child()
        p.play(watch(KEYWORD_INSIDE)).isRefusedWith(texts.deny(DenyReason.BLOCKED_KEYWORD))
    },
    row("BL-03", "the same keyword again (another spelling)") {
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/add", """{"kind":"keyword","value":"$KEYWORD"}""").isOkWith()
        p.post("/api/parent/blocklist/add", """{"kind":"keyword","value":" $KEYWORD "}""").isOkWith(texts.get(R.string.phone_keyword_exists, KEYWORD))
        assertThat(profiles.get("kid")!!.blocklist.titleKeywords).hasSize(1)
    },
    row("BL-04", "remove a keyword; one that is not there") {
        lists.addKeyword(KEYWORD)
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/remove", """{"kind":"keyword","value":"$KEYWORD"}""").isOkWith(texts.get(R.string.phone_keyword_unblocked, KEYWORD))
        p.post("/api/parent/blocklist/remove", """{"kind":"keyword","value":"$KEYWORD"}""").isRefusedWith(texts.get(R.string.phone_keyword_not_there))
    },
    row("BL-05", "a channel blocked everywhere: its video link is refused") {
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/add", """{"kind":"channel","value":"${channelLink(BLOCKED_CHANNEL)}"}""")
            .isOkWith(texts.get(R.string.phone_channel_blocked, "قناة محظورة"))
        p.play(watch(BLOCKED_VIDEO)).isRefusedWith(texts.deny(DenyReason.BLOCKED_CHANNEL))
    },
    row("BL-06", "block a link that is not a channel") {
        phone().loggedIn().post("/api/parent/blocklist/add", """{"kind":"channel","value":"${watch(INSIDE)}"}""").isRefusedWith(texts.get(R.string.phone_not_a_channel))
    },
    row("BL-07", "unblock a channel; one not blocked") {
        blockChannel()
        val p = phone().loggedIn()
        p.post("/api/parent/blocklist/remove", """{"kind":"channel","value":"$BLOCKED_CHANNEL"}""").isOkWith(texts.get(R.string.phone_channel_unblocked, "قناة محظورة"))
        p.post("/api/parent/blocklist/remove", """{"kind":"channel","value":"$BLOCKED_CHANNEL"}""").isRefusedWith(texts.get(R.string.phone_channel_not_there))
    },
    row("SS-01", "the stats: 7 days per profile, the phone's surrender counted") {
        used(25 * 60, "kid")
        lockedVideo(OUTSIDE)
        val p = phone().loggedIn()
        p.post("/api/parent/unlock-video").isOkWith()
        val stats = p.get("/api/parent/stats").arr.map { it.jsonObject }
        val kid = stats.first { it.str("profileId") == "kid" }
        assertThat(kid.getValue("days").jsonArray).hasSize(7)
        assertThat(kid.getValue("days").jsonArray.last().jsonPrimitive.intOrNull).isEqualTo(25)
        assertThat(stats.first { it.str("profileId") == "adult" }.int("surrenders")).isEqualTo(1)
    },
)

// ---- TV: what happens on the TV, as the phone meets it ----------------------------------------------------------------------

private fun tvEventRows() = listOf(
    row("TV-01", "the parent leaves the child profile on the TV: the phone follows, its parent session stays") {
        child()
        val p = phone().loggedIn()
        assertThat(p.status().str("profileKind")).isEqualTo("CHILD")
        tvSwitch("adult")
        val s = p.status()
        assertThat(s.str("profileKind")).isEqualTo("ADULT")
        assertThat(s.bool("canSearch")).isTrue()
        assertThat(p.get("/api/parent/settings").code).isEqualTo(200)
    },
    row("TV-02", "a link checked for the child, the TV switched before it opened: nothing opens") {
        child()
        screens.beforeOpening = { tvSwitch("adult") }
        phone().play(watch(INSIDE)).isRefusedWith(texts.get(R.string.remote_profile_changed))
        assertThat(screens.opened).isEmpty()
        assertThat(lock.current()).isNull()
    },
    row("TV-03", "the quota runs out while the child watches: time up, nothing new, nothing to control") {
        child()
        val p = phone()
        p.play(watch(INSIDE)).isOkWith()
        used(3600)
        releasePlayer()
        val s = p.status()
        assertThat(s.str("sessionState")).isEqualTo("TIME_UP")
        assertThat(s["nowPlaying"]).isEqualTo(JsonNull)
        assertThat(s.bool("videoLocked")).isTrue()
        p.play(watch(INSIDE2)).isRefusedWith()
        p.control("PAUSE").isRefusedWith(texts.get(R.string.remote_nothing_playing))
    },
    row("TV-04", "the last minutes: WARNING") {
        child()
        val p = phone()
        assertThat(p.status().str("sessionState")).isEqualTo("OK")
        used(56 * 60)
        assertThat(p.status().str("sessionState")).isEqualTo("WARNING")
    },
    row("TV-05", "bedtime comes: OUTSIDE_SCHEDULE") {
        child()
        at("2026-09-28T21:59")
        val p = phone()
        assertThat(p.status().str("sessionState")).isEqualTo("WARNING")
        at("2026-09-28T22:00")
        assertThat(p.status().str("sessionState")).isEqualTo("OUTSIDE_SCHEDULE")
    },
    row("TV-06", "a child's single video ends by itself: the lock ends, STOP is allowed") {
        child()
        val v = lockedVideo(INSIDE)
        lock.onVideoEnded("kid", INSIDE, nextVideoId = null, played = PlayedPart(playedMs = 600_000, leftMs = 600_000))
        v.ended = true
        assertThat(lock.current()).isNull()
        val p = phone()
        assertThat(p.status().bool("videoLocked")).isFalse()
        p.control("STOP").isOkWith()
    },
    row("TV-07", "a child's playlist video ends, the next follows: the lock moves to it") {
        child()
        phone().play(playlistLink(ALLOWED_PLAYLIST)).isOkWith()
        assertThat(lock.current()!!.videoId).isEqualTo(PL_VIDEO_1)
        lock.onVideoEnded("kid", PL_VIDEO_1, nextVideoId = PL_VIDEO_2, played = PlayedPart(playedMs = 600_000, leftMs = 600_000))
        assertThat(lock.current()!!.videoId).isEqualTo(PL_VIDEO_2)
        assertThat(phone().status().bool("videoLocked")).isTrue()
    },
    row("TV-08", "the adult escapes on the TV (hold + phrase): unlocked, a surrender stored") {
        lockedVideo(OUTSIDE)
        val phrase = engine.config().escape!!.phrases.first()
        val result = lock.exitWithChallenge(heldMillis = 10_000, phrase = phrase, typed = phrase)
        assertThat(result).isInstanceOf(com.ahmadnayfeh.shasha.lock.ChallengeResult.Exited::class.java)
        assertThat(phone().status().bool("videoLocked")).isFalse()
        assertThat(usage.surrenders("adult", 0)).hasSize(1)
    },
    row("TV-09", "the adult's escape off: holding BACK does nothing") {
        adultEscape(false)
        lockedVideo(OUTSIDE)
        val phrase = com.ahmadnayfeh.shasha.policy.AdultExitChallenge.phrases.first()
        val result = lock.exitWithChallenge(heldMillis = 60_000, phrase = phrase, typed = phrase)
        assertThat(result).isEqualTo(com.ahmadnayfeh.shasha.lock.ChallengeResult.Refused)
        assertThat(phone().status().bool("videoLocked")).isTrue()
    },
    row("TV-10", "Serious Mode switched off on the TV: the lock ends without a surrender, the phone sees it") {
        lockedVideo(OUTSIDE)
        val p = phone()
        val before = p.status().long("rev")
        serious(false)
        val s = p.status()
        assertThat(s.bool("seriousMode")).isFalse()
        assertThat(s.bool("videoLocked")).isFalse()
        assertThat(usage.surrenders("adult", 0)).isEmpty()
        waitFor("rev moves") { p.status().long("rev") != before }
    },
    row("TV-11", "the guard after the phone's actions") {
        settle()
        assertThat(guard().enforce).isFalse()
        val p = phone().loggedIn()
        p.switchTo("kid").isOkWith()
        settle()
        assertThat(guard()).isEqualTo(GuardDemand(enforce = true, child = true))
        used(3600)
        p.unlockTime(15).isOkWith()
        settle()
        assertThat(guard()).isEqualTo(GuardDemand(enforce = true, child = true))
        p.switchTo("adult", MX_PIN).isOkWith()
        settle()
        assertThat(guard().enforce).isFalse()
        lockedVideo(OUTSIDE)
        assertThat(guard().enforce).isTrue()
        p.post("/api/parent/unlock-video").isOkWith()
        waitFor("the guard lets go") { !guard().enforce }
    },
    row("TV-12", "a setting changed on the TV: the mark moves, the phone's stale change is refused") {
        val p = phone().loggedIn()
        val before = p.status().long("rev")
        behavior.setNumber(BehaviorKeys.WARNING_MINUTES, 10)
        waitFor("rev moves") { p.status().long("rev") != before }
        p.setting("warning_minutes", "3", base = "5").isRefusedWith()
        assertThat(behavior.current().warningMinutes).isEqualTo(10)
    },
    row("TV-13", "a new pairing token on the TV: 401 everywhere, sessions and counts gone") {
        val p = phone().loggedIn()
        p.login(MX_WRONG)
        val fresh = runtime.regenerateToken()
        p.get("/api/status").isError(401, "pairing")
        p.token = fresh
        p.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(phonePin.status().wrong).isEqualTo(0)
    },
    row("TV-14", "a new parent PIN on the TV: the phone's session ends, the new PIN logs in") {
        val p = phone().loggedIn()
        parentPin.change(MX_PIN, "8642")
        waitFor("revoked") { p.get("/api/parent/settings").code == 403 }
        p.login("8642").isOkWith()
    },
    row("TV-15", "the TV asks who is watching (relock): a link waits for it; the phone may pick a child") {
        addChild2()
        val root = root()
        waitFor("the picker at start") { root.screen.value == RootScreen.Picker(PickerMode.START) }
        screens.answer = ShowResult.NO_PROFILE // what TvDisplay answers while the picker is up
        phone().play(watch(OUTSIDE)).isRefusedWith(texts.get(R.string.remote_no_profile))
        screens.answer = ShowResult.SHOWN
        phone().switchTo("kid2").isOkWith()
        waitFor("the child's screens") { root.screen.value == RootScreen.Browse }
        assertThat(current().id).isEqualTo("kid2")
    },
    row("TV-16", "a child guessed wrong PINs on the TV: the phone's login and switch work") {
        child()
        lockOutTvPin()
        val p = phone()
        p.login().isOkWith()
        p.switchTo("adult", MX_PIN).isOkWith()
        assertThat(current().id).isEqualTo("adult")
    },
)

// ---- P2: two phones ------------------------------------------------------------------------------------------------------------

private fun twoPhoneRows() = listOf(
    row("P2-01", "both paired: what one does, both see") {
        serious(false)
        startVideo(OUTSIDE)
        val a = phone("A")
        val b = phone("B")
        a.control("PAUSE").isOkWith()
        assertThat(a.status().getValue("nowPlaying").jsonObject.bool("isPlaying")).isFalse()
        assertThat(b.status().getValue("nowPlaying").jsonObject.bool("isPlaying")).isFalse()
    },
    row("P2-02", "A logged in, B not: B is refused") {
        phone("A").loggedIn()
        phone("B").get("/api/parent/settings").isError(401, "parent_session")
    },
    row("P2-03", "A logs out: B stays in") {
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        a.post("/api/parent/logout").isOkWith()
        a.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(b.get("/api/parent/settings").code).isEqualTo(200)
    },
    row("P2-04", "both on the settings page: B's change over A's newer value is refused with A's value") {
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        assertThat(a.settingsPage().setting("warning_minutes").int("value")).isEqualTo(5)
        a.setting("warning_minutes", "10", base = "5").isOkWith()
        val r = b.setting("warning_minutes", "3", base = "5").isRefusedWith()
        assertThat(r.obj.setting("warning_minutes").int("value")).isEqualTo(10)
        assertThat(behavior.current().warningMinutes).isEqualTo(10)
    },
    row("P2-05", "B guesses wrong PINs: A is locked out too (one count); a new token frees both") {
        val a = phone("A")
        val b = phone("B")
        repeat(5) { b.login(MX_WRONG) }
        a.login().isError(429, "locked_out")
        val fresh = runtime.regenerateToken()
        a.token = fresh
        b.token = fresh
        a.login().isOkWith()
    },
    row("P2-06", "A pauses, B plays right after: the last one wins") {
        serious(false)
        val v = startVideo(OUTSIDE)
        phone("A").control("PAUSE").isOkWith()
        phone("B").control("PLAY").isOkWith()
        assertThat(v.playing).isTrue()
        assertThat(v.commands).containsExactly(PlayerCommand.Pause, PlayerCommand.Play).inOrder()
    },
    row("P2-07", "two parents each add 15 minutes: 30 in all") {
        child()
        used(3600)
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        val ra = scope.async(Dispatchers.IO) { a.unlockTime(15) }
        val rb = scope.async(Dispatchers.IO) { b.unlockTime(15) }
        ra.await().isOkWith()
        rb.await().isOkWith()
        assertThat(a.status().int("timeLeftMinutes")).isEqualTo(30)
    },
    row("P2-08", "the port moves: both pages read movingTo, both follow, both still logged in") {
        val a = phone("A").loggedIn()
        val b = phone("B").loggedIn()
        val target = MxTv.freePort()
        a.setting("remote_port", target.toString()).isOkWith()
        waitFor("announced to B") { runCatching { b.status().int("movingTo") }.getOrNull() == target || port == target }
        waitFor("moved", 10_000) { (runtime.state.value as? RemoteState.Running)?.port == target }
        a.port = target
        b.port = target
        assertThat(a.get("/api/parent/settings").code).isEqualTo(200)
        assertThat(b.get("/api/parent/settings").code).isEqualTo(200)
    },
    row("P2-09", "A cannot reach the TV: B is not affected") {
        val a = phone("A").apply { port = MxTv.freePort() } // nothing listens there: A is "offline"
        assertThat(runCatching { a.get("/api/status") }.isFailure).isTrue()
        assertThat(phone("B").get("/api/status").code).isEqualTo(200)
    },
    row("P2-10", "A restores a backup with another PIN: both sessions end") {
        val a = phone("A").loggedIn()
        val exported = a.get("/api/parent/backup").body
        parentPin.change(MX_PIN, "8642")
        waitFor("revoked") { a.get("/api/parent/settings").code == 403 }
        a.login("8642").isOkWith()
        val b = phone("B").loggedIn("8642")
        a.call("POST", "/api/parent/backup", exported.toByteArray()).isOkWith()
        a.get("/api/parent/settings").isError(403, "parent_session")
        b.get("/api/parent/settings").isError(403, "parent_session")
    },
    row("P2-11", "A changes a profile rule: B's mark moves") {
        val a = phone("A").loggedIn()
        val b = phone("B")
        val before = b.status().long("rev")
        a.post("/api/parent/profile/flags", """{"profileId":"kid","hideShorts":false}""").isOkWith()
        waitFor("B's rev moves") { b.status().long("rev") != before }
    },
)

private suspend fun MxPhone.loggedIn(pin: String): MxPhone {
    login(pin).isOkWith()
    return this
}

// ---- NW: network, restarts, the port ----------------------------------------------------------------------------------------------

private fun networkRows() = listOf(
    row("NW-01", "the phone cannot reach the TV: no answer at all") {
        val p = phone().apply { port = MxTv.freePort() }
        assertThat(runCatching { p.get("/api/status") }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    },
    row("NW-02", "the TV loses its network: the server stops, parent sessions end") {
        val p = phone().loggedIn()
        val old = port
        address = null
        assertThat(runtime.refresh()).isEqualTo(RemoteState.NoNetwork)
        p.port = old
        assertThat(runCatching { p.get("/api/status") }.isFailure).isTrue()
    },
    row("NW-03", "the network returns on the same address: same port, same token; the parent logs in again") {
        val p = phone().loggedIn()
        val old = port
        address = null
        runtime.refresh()
        address = MX_LOOPBACK
        assertThat(runtime.refresh()).isInstanceOf(RemoteState.Running::class.java)
        assertThat(port).isEqualTo(old)
        assertThat(p.get("/api/status").code).isEqualTo(200)
        p.get("/api/parent/settings").isError(403, "parent_session")
        assertThat(p.loggedIn().get("/api/parent/settings").code).isEqualTo(200)
    },
    row("NW-04", "the TV gets a new address: the server moves there; the old address answers nothing") {
        val p = phone().loggedIn()
        address = InetAddress.getByName("127.0.0.2") as Inet4Address
        val state = runtime.refresh()
        assertThat(state).isInstanceOf(RemoteState.Running::class.java)
        assertThat((state as RemoteState.Running).address.hostAddress).isEqualTo("127.0.0.2")
        assertThat(runCatching { p.get("/api/status") }.isFailure).isTrue()
    },
    row("NW-05", "the phone moves the port: answer first, then the server moves; same token, same session") {
        val p = phone().loggedIn()
        val old = port
        val target = MxTv.freePort()
        p.setting("remote_port", target.toString()).isOkWith().let { assertThat(it.obj.int("reconnectPort")).isEqualTo(target) }
        waitFor("moved", 10_000) { (runtime.state.value as? RemoteState.Running)?.port == target }
        p.port = old
        assertThat(runCatching { p.get("/api/status") }.isFailure).isTrue()
        p.port = target
        assertThat(p.get("/api/parent/settings").code).isEqualTo(200)
    },
    row("NW-06", "the TV moves the port: announced in the status first, then moved") {
        val p = phone().loggedIn()
        val target = MxTv.freePort()
        remoteSettings.setPort(target)
        val move = scope.async { runtime.restart(announceMs = 1_000) }
        waitFor("announced") { runCatching { p.status().int("movingTo") }.getOrNull() == target }
        move.await()
        p.port = target
        assertThat(p.get("/api/parent/settings").code).isEqualTo(200)
    },
    row("NW-07", "an import in flight when the server stops: it completes") {
        val p = phone().loggedIn()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        duringSync = { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val upload = scope.async(Dispatchers.IO) { runCatching { p.takeout(csvOf(UC_A to "أ", UC_B to "ب").toByteArray()) } }
        withContext(Dispatchers.IO) { assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue() }
        runtime.stop()
        release.countDown()
        waitFor("the import finished", 10_000) { syncsFinished == 1 }
        assertThat(subscriptions.observe().first().map { it.channelId }).containsExactly(UC_A, UC_B)
        upload.await()
    },
    row("NW-08", "the app restarts: the token is kept, the sessions are not, the phone's lockout is") {
        val p = phone().loggedIn()
        repeat(5) { phone("B").login(MX_WRONG) }
        val (server, newPort) = restartedServer()
        p.port = newPort
        assertThat(p.get("/api/status").code).isEqualTo(200)
        p.get("/api/parent/settings").isError(403, "parent_session")
        p.login().isError(429, "locked_out")
        server.stop()
    },
    row("NW-09", "stop and start of the same server (a network change): the brake survives, the sessions do not") {
        val p = phone().loggedIn()
        repeat(5) { phone("B").login(MX_WRONG) }
        phonePin.forget() // only the server's brake is left
        runtime.stop()
        assertThat(runtime.refresh()).isInstanceOf(RemoteState.Running::class.java)
        p.get("/api/parent/settings").isError(403, "parent_session")
        p.login().isError(429, "locked_out")
    },
    row("NW-10", "the preferred port is taken: the server takes another, the QR follows") {
        val taken = ServerSocket(0, 50, MX_LOOPBACK)
        try {
            runtime.stop()
            remoteSettings.rememberPort(taken.localPort)
            val state = runtime.refresh()
            assertThat(state).isInstanceOf(RemoteState.Running::class.java)
            assertThat((state as RemoteState.Running).port).isNotEqualTo(taken.localPort)
            assertThat(runtime.pairingUrl()).contains(":${state.port}/")
        } finally {
            taken.close()
        }
    },
)

// ======================================================================================================================
// The phone page against the same TV, for the browser checks of the matrix (UI rows): not a test by itself.
// ======================================================================================================================

/**
 * Serves the phone page on 127.0.0.1 in front of [MxTv] (the TV's real rules) for the Playwright checks of the matrix
 * (scenarios/ui.mjs). Skipped unless SHASHA_MATRIX_DIR names a directory: it writes the pairing link to "ready" there and
 * runs the commands a script writes to "cmd" (answers in "cmd.out") until a "stop" file appears (20 minutes at most).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ScenarioMatrixPageServer {
    @get:Rule
    val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun serveThePageForTheMatrixUiRows() {
        val dir = System.getenv("SHASHA_MATRIX_DIR")?.let(::File)
        org.junit.Assume.assumeTrue("SHASHA_MATRIX_DIR is not set", dir != null && dir.isDirectory)
        System.setProperty("sun.net.http.retryPost", "false")
        Dispatchers.setMain(Dispatchers.Unconfined)
        val tv = MxTv(tmp.root)
        try {
            runBlocking { serve(tv, dir!!) }
        } finally {
            tv.close()
            Dispatchers.resetMain()
        }
    }

    private suspend fun serve(tv: MxTv, dir: File) {
        listOf("stop", "ready", "cmd", "cmd.run", "cmd.out").forEach { File(dir, it).delete() }
        tv.source.searchResults = listOf(tv.source.video(OUTSIDE), tv.source.video(PL_VIDEO_1), tv.source.video(LIVE))
        File(dir, "ready").writeText("http://127.0.0.1:${tv.port}/?t=${tv.token}\n")
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        var day = 0
        while (!File(dir, "stop").exists() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val cmd = File(dir, "cmd")
            val run = File(dir, "cmd.run")
            if (cmd.exists() && cmd.renameTo(run)) {
                val answers = run.readLines().filter { it.isNotBlank() }.map { line ->
                    val args = line.trim().split(Regex("\\s+"))
                    try {
                        when (args[0]) {
                            // A new day at noon: no usage, no grants; the adult on the TV, nothing locked or playing.
                            "fresh" -> {
                                day++
                                tv.lock.unlockByParent()
                                tv.releasePlayer()
                                tv.behavior.resetAll()
                                withTimeout(5_000) { tv.behavior.values.first { it == com.ahmadnayfeh.shasha.data.Behavior.DEFAULT } }
                                tv.at("2026-10-%02dT12:00".format(day))
                                if (tv.current().id != "adult") tv.tvSwitch("adult")
                                tv.quota("kid", 60)
                                tv.phonePin.forget()
                                tv.runtime.server.forgetPinFailures()
                                "ok"
                            }
                            "child" -> { tv.child(); "ok" }
                            "used" -> { tv.used(args[1].toLong(), args.getOrElse(2) { "kid" }); "ok" }
                            "at" -> {
                                // "23:00": today at that time; or a whole local date-time.
                                val t = args[1]
                                tv.at(if (t.length == 5) "${java.time.LocalDate.now(tv.clock)}T$t" else t)
                                "ok"
                            }
                            "play" -> { tv.startVideo(args[1], live = args.getOrNull(2) == "live"); "ok" }
                            "release" -> { tv.releasePlayer(); "ok" }
                            "serious" -> { tv.serious(args[1] == "on"); "ok" }
                            "aep" -> { tv.adultEntryPin(args[1] == "on"); "ok" }
                            "extra" -> {
                                val minutes = args[1].split(',').map { it.toInt() }
                                tv.behavior.setExtraMinutes(minutes)
                                withTimeout(5_000) { tv.behavior.values.first { it.extraMinutes == minutes.sorted() } }
                                "ok"
                            }
                            "revoke" -> { tv.runtime.revokeParentSessions(); "ok" }
                            "phonelock" -> { tv.lockOutPhonePin(); "ok" }
                            "quota" -> { tv.quota(args[1], args[2].toIntOrNull()); "ok" }
                            "status" -> tv.controller.status().let { "kind=${it.profileKind} state=${it.sessionState} locked=${it.videoLocked} playing=${it.nowPlaying?.videoId}" }
                            "locked" -> (tv.lock.current() != null).toString()
                            else -> "unknown ${args[0]}"
                        }
                    } catch (e: Throwable) {
                        "ERROR ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                run.delete()
                File(dir, "cmd.out.tmp").writeText(answers.joinToString("\n") + "\n")
                File(dir, "cmd.out.tmp").renameTo(File(dir, "cmd.out"))
            }
            delay(40)
        }
    }
}

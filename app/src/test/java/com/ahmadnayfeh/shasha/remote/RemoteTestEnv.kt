package com.ahmadnayfeh.shasha.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.ahmadnayfeh.shasha.backup.BackupManager
import com.ahmadnayfeh.shasha.backup.RestoreReport
import com.ahmadnayfeh.shasha.data.ActiveProfile
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.ahmadnayfeh.shasha.data.OverridesRepository
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PlaybackLockStore
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.TestDataStores
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.data.cancelAndWait
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.data.testScope
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.ActivePlayer
import com.ahmadnayfeh.shasha.player.NowPlaying
import com.ahmadnayfeh.shasha.player.PlayQueues
import com.ahmadnayfeh.shasha.player.PlayerCommand
import com.ahmadnayfeh.shasha.player.PlayerHub
import com.ahmadnayfeh.shasha.player.PlayerRefusal
import com.ahmadnayfeh.shasha.policy.ALLOWED_CHANNEL
import com.ahmadnayfeh.shasha.policy.ALLOWED_PLAYLIST
import com.ahmadnayfeh.shasha.policy.LookedUpVideo
import com.ahmadnayfeh.shasha.policy.MutableClock
import com.ahmadnayfeh.shasha.policy.OTHER_CHANNEL
import com.ahmadnayfeh.shasha.policy.PinHasher
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.PolicyGate
import com.ahmadnayfeh.shasha.policy.SessionController
import com.ahmadnayfeh.shasha.policy.SwitchResult
import com.ahmadnayfeh.shasha.policy.VideoLookup
import com.ahmadnayfeh.shasha.policy.clip
import com.ahmadnayfeh.shasha.policy.instantAt
import com.ahmadnayfeh.shasha.policy.kid
import com.ahmadnayfeh.shasha.policy.owner
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val PIN = "1357"
const val WRONG_PIN = "2468"

/** Links as the phone sends them. */
const val INSIDE_VIDEO = "inside00001"
const val OUTSIDE_VIDEO = "outside0001"
const val ADULT_VIDEO = "adultvideo1"

/**
 * The TV's real rules behind the phone remote, on an in-memory database and DataStore files in [dir]: the
 * owner ("adult") and a child ("kid", [ALLOWED_CHANNEL] and [ALLOWED_PLAYLIST] allowlisted, 60 minutes a
 * day, 07:00–22:00), the parent PIN [PIN]. Only YouTube, the player and the screens are fakes. The clock
 * starts on 2026-09-28 at noon (Riyadh). [afterRestore] runs at the end of a restore, like AppContainer's.
 */
class RemoteTestEnv(dir: File, afterRestore: suspend (RestoreReport) -> Unit = {}) {
    val clock = MutableClock(instantAt("2026-09-28T12:00"))
    // The household's behaviour (v1.1): the engine reads it as the app's does.
    val engine = PolicyEngine(clock) { behavior.policyConfig() }
    val scope = testScope()
    val db: ShashaDatabase = inMemoryDatabase()
    val stores = TestDataStores(File(dir, "stores").apply { mkdirs() })
    val policyStore: DataStore<Preferences> = stores.open()
    val remoteStore: DataStore<Preferences> = stores.open(RemoteSettings.FILE_NAME)
    val behavior = BehaviorSettings(stores.open(BehaviorSettings.FILE_NAME), scope)
    val sponsorSettings = SponsorBlockSettings(stores.open(SponsorBlockSettings.FILE_NAME))
    val playbackSettings = PlaybackSettings(stores.open(PlaybackSettings.FILE_NAME), scope)

    /** Server restarts the phone settings asked for (a new port). */
    @Volatile var restarts = 0
    val filesDir = File(dir, "files").apply { mkdirs() }

    val source = RemoteFakeSource()
    val profiles = ProfileRepository(db)
    val usage = UsageRepository(db)
    val overrides = OverridesRepository(policyStore)
    val parentPin = ParentPinRepository(policyStore, clock, PinHasher(iterations = 1_000))
    val active: ActiveProfile
    val session: SessionController
    val locks = PlaybackLockStore(policyStore, scope)
    val gate: PolicyGate
    val lock: LockController
    val subscriptions: SubscriptionRepository
    val remoteSettings = RemoteSettings(remoteStore)
    val phonePin = PhonePin(policyStore, clock)
    val profileChoices = PhoneProfileChoices()
    val backup: BackupManager
    val hub = PlayerHub()
    val display = FakeDisplay()
    val texts = RemoteTexts(ApplicationProvider.getApplicationContext<Context>().resources)
    val controller: TvRemoteController

    /** Feed syncs the subscriptions asked for (new channels). */
    @Volatile var syncs = 0

    init {
        runBlocking {
            profiles.ensureOwner(owner().name)
            profiles.upsert(kid())
            parentPin.setup(PIN)
        }
        active = ActiveProfile.create(profiles, policyStore, scope, ownerName = owner().name)
        session = SessionController(engine, active.profile, usage, overrides, scope).also { it.start() }
        val lookup = VideoLookup { id ->
            LookedUpVideo(source.videos[id] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", id, null), null)
        }
        gate = PolicyGate(engine, active, profiles, session, locks, parentPin, lookup, scope, surrenders = { usage.recordSurrender(it) })
        lock = LockController(
            engine = engine,
            store = locks,
            profile = active.profile,
            verifyPin = { gate.verifyParentPin(it) },
            pinLockMillis = { parentPin.remainingLockMillis() },
            surrenders = { usage.recordSurrender(it) },
            scope = scope,
        )
        subscriptions = SubscriptionRepository(db, source, clock = { clock.millis() }, onChannelsAdded = { syncs++ })
        backup = BackupManager(
            db = db,
            policyStore = policyStore,
            sections = listOf(RemoteSettings.backupSection(remoteStore)),
            clock = clock,
            appVersion = "test",
            filesDir = { filesDir },
            afterRestore = afterRestore,
        )
        controller = newController(phonePin)
        source.videos[INSIDE_VIDEO] = clip(INSIDE_VIDEO, channelId = ALLOWED_CHANNEL, title = "Allowed cartoon")
        source.videos[OUTSIDE_VIDEO] = clip(OUTSIDE_VIDEO, channelId = OTHER_CHANNEL, title = "Somewhere else")
        source.videos[ADULT_VIDEO] = clip(ADULT_VIDEO, channelId = OTHER_CHANNEL, title = "Documentary")
        runBlocking { active.awaitProfile() }
    }

    /**
     * A controller over this TV's rules with [phonePin]: `PhonePin(policyStore, clock)` is the phone's count as a new
     * process reads it back from the store.
     */
    fun newController(phonePin: PhonePin) = TvRemoteController(
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
        display = display,
        playQueues = PlayQueues(),
        texts = texts,
        main = Dispatchers.Unconfined,
        seriousMode = { behavior.policyConfig().seriousMode },
        profileChoices = profileChoices,
        settings = PhoneSettings(
            behavior = behavior,
            sponsorBlock = sponsorSettings,
            playback = playbackSettings,
            remote = remoteSettings,
            resources = ApplicationProvider.getApplicationContext<Context>().resources,
            scope = scope,
            restartRemote = { restarts++ },
            restartDelayMs = 50,
        ),
        parental = PhoneParental(
            profiles = profiles,
            lists = ParentalLists(profiles),
            usage = usage,
            engine = engine,
            zone = { clock.zone },
            source = source,
            resources = ApplicationProvider.getApplicationContext<Context>().resources,
            errors = texts::sourceError,
            currentId = { active.awaitProfile().id },
        ),
    )

    fun close() {
        scope.cancelAndWait()
        stores.closeAll()
        db.close()
    }

    suspend fun current() = active.awaitProfile()

    /** Makes the child profile current (no PIN needed to enter it) and waits until the rules follow. */
    suspend fun enterChild() {
        assertThat(gate.switchProfile("kid", pin = null)).isEqualTo(SwitchResult.Switched)
        withTimeout(5_000) { gate.filter.first { it.profile?.id == "kid" } }
    }

    /** Five wrong PINs on the TV: its attempt limiter locks every TV PIN check out. */
    suspend fun lockOutTheTvPin() {
        repeat(5) { parentPin.verify(WRONG_PIN) }
        assertThat(parentPin.remainingLockMillis()).isGreaterThan(0L)
    }

    /** The player on screen playing [videoId] (as PlayerScreen would attach it). */
    fun playOnScreen(videoId: String, live: Boolean = false): FakeActivePlayer = FakeActivePlayer(videoId, live).also { hub.attach(it) }

    /** Today's usage of [profileId], FOREGROUND, as the tracker would have recorded it. */
    suspend fun used(profileId: String, seconds: Long) {
        usage.addForeground(profileId, engine.today(), seconds)
        session.refresh()
    }
}

fun remoteEnv(dir: File, afterRestore: suspend (RestoreReport) -> Unit = {}) = RemoteTestEnv(dir, afterRestore)

/** Records what the TV was asked to show, and for which profile; [result] is what it answers. */
class FakeDisplay : RemoteDisplay {
    val shown = CopyOnWriteArrayList<Screen>()
    val shownFor = CopyOnWriteArrayList<String>()

    @Volatile var result = ShowResult.SHOWN

    override suspend fun show(destination: Screen, profileId: String): ShowResult {
        if (result == ShowResult.SHOWN) {
            shown += destination
            shownFor += profileId
        }
        return result
    }
}

/** A player on screen that records what the phone asked of it. */
class FakeActivePlayer(override val videoId: String, private val live: Boolean = false) : ActivePlayer {
    val commands = CopyOnWriteArrayList<PlayerCommand>()

    @Volatile var playing = true

    @Volatile var ended = false

    @Volatile var covered = false

    @Volatile var left = 0

    override fun nowPlaying() = NowPlaying(
        videoId = videoId,
        title = "Title of $videoId",
        channelName = "Channel",
        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        positionMs = 42_500,
        durationMs = if (live) null else 600_000,
        playing = playing,
        live = live,
        ended = ended,
    )

    override fun perform(command: PlayerCommand): PlayerRefusal? {
        if (ended) return PlayerRefusal.ENDED
        if (command is PlayerCommand.SeekBy && live) return PlayerRefusal.LIVE
        if (covered && (command == PlayerCommand.Play || command == PlayerCommand.Toggle && !playing)) return PlayerRefusal.COVERED
        commands += command
        when (command) {
            PlayerCommand.Play -> playing = true
            PlayerCommand.Pause -> playing = false
            PlayerCommand.Toggle -> playing = !playing
            is PlayerCommand.SeekBy -> Unit
        }
        return null
    }

    override fun leave() {
        left++
    }
}

/**
 * YouTube as maps: [videos] (what an extraction says), [channels], [handles] (@name → UC…), [playlists],
 * [searchResults]. [parseLink] understands the usual link shapes (watch?v= with list= and t=, youtu.be,
 * /shorts/, playlist?list=, /channel/UC…, /@handle).
 */
class RemoteFakeSource : VideoSource {
    override val extractorVersion = "test"

    val videos = ConcurrentHashMap<String, VideoItem>()
    val channels = ConcurrentHashMap<String, ChannelItem>()
    val handles = ConcurrentHashMap<String, String>()
    val playlists = ConcurrentHashMap<String, PlaylistPage>()

    @Volatile var searchResults: List<SourceItem> = emptyList()

    @Volatile var searchFailure: SourceException? = null
    val searches = CopyOnWriteArrayList<String>()

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        searches += query
        searchFailure?.let { throw it }
        return ItemsPage(searchResults, next = null)
    }

    override suspend fun channel(channelId: String): ChannelDetails =
        channels[channelId]?.let { ChannelDetails(it, bannerUrl = null, description = null) }
            ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", channelId, null)

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = ItemsPage(emptyList(), null)

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage =
        playlists[playlistId] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", playlistId, null)

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails =
        throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NotInTests", "StreamDetails cannot be built outside :youtube", null)

    override suspend fun recentVideos(channelId: String): List<VideoItem> = emptyList()

    override fun parseLink(text: String): YouTubeLink? {
        val t = text.trim()
        val list = Regex("[?&]list=([0-9A-Za-z_-]+)").find(t)?.groupValues?.get(1)
        val start = Regex("[?&#]t=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull()
        Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/live/)([0-9A-Za-z_-]{11})").find(t)?.let { return YouTubeLink.Video(it.groupValues[1], start, list) }
        if (list != null) return YouTubeLink.Playlist(list)
        Regex("/channel/(UC[0-9A-Za-z_-]{22})").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        Regex("youtube\\.com/(@[^/?#\\s]+)").find(t)?.let { return YouTubeLink.Channel(it.groupValues[1]) }
        return null
    }

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String =
        if (link.raw.startsWith("UC")) link.raw
        else handles[link.raw] ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "UnresolvedChannel", link.raw, null)

    fun addPlaylist(id: String, title: String, videos: List<VideoItem>) {
        playlists[id] = PlaylistPage(PlaylistItem(id, title, "Owner", null, videos.size.toLong()), videos, next = null)
    }
}

/** A link to [videoId], optionally inside [list]. */
fun watchLink(videoId: String, list: String? = null) =
    "https://www.youtube.com/watch?v=$videoId" + (list?.let { "&list=$it" } ?: "")

/** The allowlisted playlist's link. */
val ALLOWED_PLAYLIST_LINK = "https://www.youtube.com/playlist?list=$ALLOWED_PLAYLIST"

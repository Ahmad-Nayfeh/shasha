package com.ahmadnayfeh.shasha.mobile

import android.app.Application
import android.content.Context
import com.ahmadnayfeh.shasha.mobile.common.ShortsQueues
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.ChannelUploads
import com.ahmadnayfeh.shasha.mobile.data.FeedRepository
import com.ahmadnayfeh.shasha.mobile.data.HomeSuggestions
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.SavedPlaylistRepository
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.mobile.data.SettingsRepository
import com.ahmadnayfeh.shasha.mobile.data.StableSuggestions
import com.ahmadnayfeh.shasha.mobile.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.mobile.data.UserPlaylistRepository
import com.ahmadnayfeh.shasha.mobile.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.mobile.download.DownloadNotifier
import com.ahmadnayfeh.shasha.mobile.download.DownloadService
import com.ahmadnayfeh.shasha.mobile.download.MediaMuxerRemuxer
import com.ahmadnayfeh.shasha.mobile.download.MediaStoreSaver
import com.ahmadnayfeh.shasha.mobile.download.OptionsSource
import com.ahmadnayfeh.shasha.mobile.download.StreamFetcher
import com.ahmadnayfeh.shasha.mobile.player.PlayerController
import com.ahmadnayfeh.shasha.mobile.player.VideoFit
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorBlockClient
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorBlockRepository
import com.ahmadnayfeh.shasha.mobile.update.ApkInspector
import com.ahmadnayfeh.shasha.mobile.update.InstallEvents
import com.ahmadnayfeh.shasha.mobile.update.InstalledVersion
import com.ahmadnayfeh.shasha.mobile.update.UpdateFeed
import com.ahmadnayfeh.shasha.mobile.update.UpdateInstaller
import com.ahmadnayfeh.shasha.mobile.update.UpdateSteps
import com.ahmadnayfeh.shasha.mobile.update.Updater
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTube
import com.ahmadnayfeh.shasha.youtube.download.StreamDownloader
import com.ahmadnayfeh.shasha.youtube.download.downloadOptions
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** What a link, a share or a notification asks the app to open; the root screen handles it once ([AppContainer.launch]). */
sealed interface LaunchRequest {
    data class Video(val id: String, val startSeconds: Long?, val playlistId: String?) : LaunchRequest
    data class Playlist(val id: String) : LaunchRequest

    /** [raw]: "UC…", "@handle", "c/name" or "user/name". */
    data class Channel(val raw: String) : LaunchRequest
    data class Search(val query: String) : LaunchRequest
    data object Downloads : LaunchRequest
}

/**
 * The app's objects, built lazily and once per process (manual dependency injection, like the TV app): the shared
 * HTTP client, the YouTube source, the database, the settings, the repositories, the player, the downloads and the updater.
 */
open class AppContainer(private val app: Application) {
    /** Work that outlives any screen and needs no main thread. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** For what must run on the main thread beyond any screen (the player). */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    open val videoSource: VideoSource by lazy { YouTube.create(app, okHttp) }
    private val mediaSourceFactory: YouTubeMediaSourceFactory by lazy { YouTubeMediaSourceFactory(app, okHttp) }

    /** Resolved streams by video id, 90 minutes each: a pressed card, a Short ahead or a replay starts at once. */
    val streamCache: com.ahmadnayfeh.shasha.mobile.data.StreamCache by lazy {
        com.ahmadnayfeh.shasha.mobile.data.StreamCache(appScope, System::currentTimeMillis) { videoSource.stream(it, allowLowQuality = true) }
    }

    val database: ShashaDatabase by lazy { ShashaDatabase.create(app) }

    val settings: SettingsRepository by lazy {
        SettingsRepository(SettingsRepository.createStore(app, CoroutineScope(SupervisorJob() + Dispatchers.IO)))
    }

    private val settingsState = MutableStateFlow(AppSettings())

    /** The settings as they are now, kept up to date from [start]; [initialSettings] reads them once, synchronously. */
    val settingsFlow: StateFlow<AppSettings> get() = settingsState

    fun latestSettings(): AppSettings = settingsState.value

    /** Keeps the order the viewer chose for comments (the next video's comments open in it). */
    fun saveCommentSort(sort: com.ahmadnayfeh.shasha.youtube.CommentSort) {
        appScope.launch { settings.setCommentSort(sort) }
    }

    /** The «Show Shorts» setting, as a flow that says only when it changes: every screen with Shorts on it follows it. */
    val showShorts: Flow<Boolean> get() = settings.settings.map { it.showShorts }.distinctUntilChanged()

    /** Reads the stored settings now (the activity needs the language before it can show anything). Cheap: one small file. */
    fun initialSettings(): AppSettings = runBlocking { settings.settings.first() }.also { settingsState.value = it }

    val subscriptions: SubscriptionRepository by lazy { SubscriptionRepository(database.subscriptions(), database.feed()) }
    val feed: FeedRepository by lazy { FeedRepository(videoSource, database.subscriptions(), database.feed(), settings) }
    val library: LibraryRepository by lazy { LibraryRepository(database.history(), database.watchLater(), database.progress()) }
    val searchHistory: SearchHistoryRepository by lazy { SearchHistoryRepository(database.searchHistory()) }

    /** The viewer's own playlists («Watch later» is the built-in one, [library]). */
    val userPlaylists: UserPlaylistRepository by lazy { UserPlaylistRepository(database.userPlaylists()) }

    /** YouTube playlists saved to the Library. */
    val savedPlaylists: SavedPlaylistRepository by lazy { SavedPlaylistRepository(database.savedPlaylists()) }

    /** «مقترحة لك» on the home screen: the lists kept in the cache folder, refreshed beside the screen. */
    val suggestions: HomeSuggestions by lazy {
        HomeSuggestions(
            library,
            videoSource,
            File(app.cacheDir, HomeSuggestions.FILE_NAME),
            // The Shorts row: a channel's Shorts tab, else the playlist YouTube keeps of them («UUSH…»).
            channelShorts = ChannelUploads(videoSource)::shorts,
        )
    }

    /** What the Home's suggestion rows show, held still for the run of the app (see [StableSuggestions]). */
    val stableSuggestions = StableSuggestions()

    /** The lists the Shorts viewer was opened from, by the number its route carries. */
    val shortsQueues = ShortsQueues()

    /** How a video's picture sits in its frame (pinch to zoom): the viewer's choice, for the run of the app. */
    val videoFit = MutableStateFlow(VideoFit.FIT)

    val sponsorBlock: SponsorBlockRepository by lazy {
        SponsorBlockRepository(SponsorBlockClient(okHttp), database.sponsorSegments(), System::currentTimeMillis)
    }

    val player: PlayerController by lazy {
        PlayerController(
            context = app,
            videoSource = videoSource,
            mediaSources = mediaSourceFactory,
            settings = ::latestSettings,
            sponsorBlock = sponsorBlock,
            library = library,
            scope = mainScope,
            streams = streamCache,
            saveSubtitleLanguage = { code -> appScope.launch { settings.setSubtitleLanguage(code) } },
        )
    }

    val downloadNotifier: DownloadNotifier by lazy { DownloadNotifier(app) { latestSettings().language } }

    /** What a video can be downloaded as, from a fresh extraction (its streams' addresses expire). */
    val optionsSource: OptionsSource by lazy { OptionsSource { videoId -> videoSource.stream(videoId).downloadOptions() } }

    open val downloads: DownloadManager by lazy {
        val downloader = StreamDownloader(okHttp)
        DownloadManager(
            dao = database.downloads(),
            source = optionsSource,
            fetcher = StreamFetcher { stream, file, progress -> downloader.download(stream, file, progress) },
            remuxer = MediaMuxerRemuxer(),
            saver = MediaStoreSaver(app),
            workDir = File(app.cacheDir, "downloads"),
            scope = downloadScope,
            notifications = downloadNotifier,
        ).also { manager ->
            // The foreground service (with the progress notification) runs while the queue does.
            appScope.launch { manager.busy.collect { busy -> if (busy) DownloadService.start(app) } }
        }
    }

    val installEvents: InstallEvents by lazy { InstallEvents() }

    val updater: UpdateSteps by lazy {
        Updater(
            feed = UpdateFeed(okHttp, BuildConfig.UPDATE_MANIFEST_URL.toHttpUrl()),
            inspector = ApkInspector(app),
            installer = UpdateInstaller(app),
            installed = InstalledVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
            downloads = File(app.cacheDir, "updates"),
            events = installEvents,
        )
    }

    /** A link or notification waiting to be opened by the root screen (which clears it). */
    val launch = MutableStateFlow<LaunchRequest?>(null)

    /** Keeps [latestSettings] in step with the stored settings. Called once, when the app starts. */
    fun start() {
        appScope.launch { settings.settings.collect { settingsState.value = it } }
    }
}

/** The app's [AppContainer], from any context. */
val Context.container: AppContainer get() = (applicationContext as MobileApp).container

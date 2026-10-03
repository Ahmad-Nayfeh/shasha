package com.ahmadnayfeh.shasha.mobile

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ahmadnayfeh.shasha.mobile.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Waits in real time (not the test's virtual time) until [condition] holds: for work that really runs on another
 * thread, such as a DataStore write.
 */
suspend fun awaitReal(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }
}

/** The settings in a DataStore file of the test's own (plain JVM). */
fun testSettings(file: java.io.File, scope: CoroutineScope): SettingsRepository =
    SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

/**
 * Runs `Dispatchers.Main` (the view models' scope) on a test dispatcher that executes at once. A view model that is not
 * cleared keeps coroutines (a sharing flow's timeout) that wake up after the test: Main then falls back to an inline
 * dispatcher instead of being reset, so nothing dispatches into a Main that is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
}

fun video(
    id: String,
    title: String = "Video $id",
    channelId: String? = "UCchannel0000000000000$id".take(24),
    channelName: String? = "Channel",
    seconds: Long? = 300,
    publishedAt: Long? = null,
    live: Boolean = false,
) = VideoItem(
    videoId = id, title = title, channelId = channelId, channelName = channelName, durationSeconds = seconds,
    publishedAtEpochMs = publishedAt, thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg", viewCount = 1_000,
    isLive = live, isShortForm = false, url = "https://www.youtube.com/watch?v=$id",
)

fun channel(id: String = "UCchannel00000000000000", name: String = "Channel $id") =
    ChannelItem(channelId = id, name = name, avatarUrl = "https://yt3.ggpht.com/$id", subscriberCount = 12_345)

fun playlist(id: String = "PL0000000000000000", title: String = "Playlist $id") =
    PlaylistItem(playlistId = id, title = title, channelName = "Channel", thumbnailUrl = null, videoCount = 3)

fun failure(kind: SourceErrorKind = SourceErrorKind.NETWORK, name: String = "IOException") = SourceException(kind, name, "failed", null)

/** A [VideoSource] whose answers a test sets; whatever it was not told about fails. */
open class FakeVideoSource : VideoSource {
    override val extractorVersion: String = "test"

    var onSearch: suspend (String, SearchFilter, PageKey?) -> ItemsPage = { _, _, _ -> ItemsPage(emptyList(), null) }
    var onSearchShorts: suspend (String, PageKey?) -> ShortsPage = { _, _ -> ShortsPage(emptyList(), null) }
    var onSuggestions: suspend (String, String) -> List<String> = { _, _ -> emptyList() }
    var onChannel: suspend (String) -> ChannelDetails = { id -> ChannelDetails(com.ahmadnayfeh.shasha.mobile.channel(id), null, null) }
    var onChannelVideos: suspend (String, PageKey?) -> ItemsPage = { _, _ -> ItemsPage(emptyList(), null) }
    var onChannelShorts: suspend (String) -> List<VideoItem> = { emptyList() }
    var onChannelPlaylists: suspend (String, PageKey?) -> ItemsPage = { _, _ -> ItemsPage(emptyList(), null) }
    var onPlaylist: suspend (String, PageKey?) -> PlaylistPage = { id, _ -> PlaylistPage(com.ahmadnayfeh.shasha.mobile.playlist(id), emptyList(), null) }
    var onRecent: suspend (String) -> List<VideoItem> = { emptyList() }
    var onRelated: suspend (String) -> List<VideoItem> = { emptyList() }

    val searches = ArrayList<Triple<String, SearchFilter, PageKey?>>()
    val shortsSearches = ArrayList<Pair<String, PageKey?>>()
    val suggestionQueries = ArrayList<String>()

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        searches += Triple(query, filter, page)
        return onSearch(query, filter, page)
    }

    override suspend fun searchShorts(query: String, page: PageKey?): ShortsPage {
        shortsSearches += query to page
        return onSearchShorts(query, page)
    }

    override suspend fun suggestions(query: String, language: String): List<String> {
        suggestionQueries += "$query|$language"
        return onSuggestions(query, language)
    }

    override suspend fun channel(channelId: String): ChannelDetails = onChannel(channelId)
    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = onChannelVideos(channelId, page)
    override suspend fun channelShorts(channelId: String): List<VideoItem> = onChannelShorts(channelId)
    override suspend fun channelPlaylists(channelId: String, page: PageKey?): ItemsPage = onChannelPlaylists(channelId, page)
    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage = onPlaylist(playlistId, page)
    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails = error("stream() is not part of this test")
    override suspend fun related(videoId: String): List<VideoItem> = onRelated(videoId)
    override suspend fun recentVideos(channelId: String): List<VideoItem> = onRecent(channelId)
    override fun parseLink(text: String): YouTubeLink? = null
    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = link.raw
}

package com.ahmadnayfeh.shasha.mobile.data

import android.util.Log
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException

private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

/** A real channel id («UC…»), not a handle or a link's name: only that has the playlists YouTube keeps for each channel. */
fun isChannelId(id: String): Boolean = CHANNEL_ID.matches(id)

/**
 * Where a channel's list of videos can be read from, in the order they are tried ([ChannelUploads]): each is another request
 * than the one before, so one can work where another gets YouTube's error answer.
 */
enum class UploadsFrom(internal val logName: String) {
    /**
     * The channel's Videos tab ([VideoSource.channelVideos]): every video with its duration, no Shorts, about 30 a page. The
     * usual way, and the only one the page used until a phone on mobile data in Saudi Arabia answered it with
     * `ContentNotAvailableException` for the channels of a family.
     */
    VIDEOS_TAB("Videos tab"),

    /**
     * «UULF…» ([ChannelUploads.longFormPlaylistId]): the playlist YouTube keeps of each channel's long-form uploads. On real
     * YouTube (the TV app checked it, `RealNetworkKidsUploadsTest`) it is the Videos tab as a playlist: the same videos in the
     * same order, each with its duration and no Shorts, 100 a page, so paging can go on from it.
     */
    LONG_FORM_PLAYLIST("long-form uploads playlist"),

    /**
     * «UU…» ([ChannelUploads.allUploadsPlaylistId]): every upload, with the Shorts and the live streams among them (none of
     * them marked as such). After «UULF…» for that reason; it is what a channel that has no long-form playlist (only Shorts)
     * still has.
     */
    ALL_UPLOADS_PLAYLIST("uploads playlist"),

    /**
     * The channel's RSS feed ([VideoSource.recentVideos], how the home feed reads it): the newest 15 to 30 uploads with exact
     * dates, but no durations and no next page. Its Shorts (marked by their link) are left out: they have a tab of their own.
     */
    RSS("RSS feed"),
}

/** One page of a channel's videos. [next] continues the list [from] gave: hand both to [ChannelUploads.nextPage]. */
class UploadsPage(val videos: List<VideoItem>, val next: PageKey?, val from: UploadsFrom)

/**
 * A channel's videos (and its Shorts), read through a chain of ways to get them instead of one, so that a way YouTube answers
 * with an error on one network does not leave the channel's page empty. The TV app has such a chain of its own
 * (`:app` ChannelUploads, written for a TV in Saudi Arabia that got `ContentNotAvailableException` for every channel tab);
 * the phone shares no code with it, and its page differs: no profile hides Shorts, and a list needs no durations.
 *
 * **The videos** ([firstPage], each step only when the ones before it failed or came back empty; every failure, and the way that
 * answered instead, is logged with `Log.w`, tag `ChannelUploads`, with the channel's id and what YouTube said, for `adb logcat`):
 * 1. [UploadsFrom.VIDEOS_TAB];
 * 2. [UploadsFrom.LONG_FORM_PLAYLIST];
 * 3. [UploadsFrom.ALL_UPLOADS_PLAYLIST];
 * 4. [UploadsFrom.RSS].
 *
 * «UU…» is a step here and is not on the TV: the TV hides Shorts from some profiles, and «UU…» cannot tell them from videos
 * (none is flagged). The phone's page has no such rule, and «UU…» is the playlist a channel always has.
 *
 * **The Shorts** ([shorts]): the Shorts tab, then «UUSH…», the playlist YouTube keeps of them.
 *
 * A step that answered with an empty list is an answer, not a failure: a channel with no long-form videos has an empty Videos
 * tab and may have no «UULF…» playlist at all. The next steps are still asked (the tab answers an empty page where YouTube
 * changed something, too), but if none has videos the answer is the empty list, not an error. An empty page that has a next
 * page is a list all the same (the content filter may hold a whole page back) and ends the chain. When no step answered, the
 * error thrown is the first one: the Videos tab's.
 *
 * Three failures end the chain, because the next step would only repeat them: a connectivity failure
 * ([SourceErrorKind.NETWORK]: offline, DNS, a timeout; the next step would wait as long for nothing), a rate limit
 * ([SourceErrorKind.RATE_LIMITED]: asking again at once only prolongs it) and a channel that is gone
 * ([SourceErrorKind.ACCOUNT_TERMINATED]).
 */
class ChannelUploads(private val source: VideoSource) {

    /**
     * The first page of the channel's videos, from the first step of the chain that answers with videos.
     * @throws Exception the Videos tab's failure when no step answered.
     */
    suspend fun firstPage(channelId: String): UploadsPage {
        var firstError: Exception? = null
        var emptyAnswer: UploadsPage? = null
        for (step in UploadsFrom.entries) {
            if (step.needsChannelId && !isChannelId(channelId)) continue // a handle has no playlists of these
            try {
                val page = read(step, channelId, key = null)
                // A page with a next one is a list even when nothing on it is left to show (the content filter may have held it all back).
                if (page.videos.isNotEmpty() || page.next != null) {
                    firstError?.let {
                        val text = "$channelId: using the ${step.logName} instead (${page.videos.size} videos) after: ${it.logLine()}"
                        if (emptyAnswer == null) Log.w(TAG, text) else Log.i(TAG, text)
                    }
                    return page
                }
                emptyAnswer = emptyAnswer ?: page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = "$channelId: the ${step.logName} failed: ${e.logLine()}"
                // After a step that answered "no videos" this is what a channel without long-form videos looks like: not a warning.
                if (emptyAnswer == null) Log.w(TAG, text) else Log.i(TAG, text)
                firstError = firstError ?: e
                if (e.endsTheChain()) break
            }
        }
        emptyAnswer?.let { return it }
        throw firstError ?: SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NoUploadsSource", "No way to list the videos of $channelId", null)
    }

    /** The page after [key], from the list [from] gave the page before (RSS has one page only). */
    suspend fun nextPage(channelId: String, from: UploadsFrom, key: PageKey): UploadsPage = read(from, channelId, key)

    /**
     * The first page of the channel's Shorts, newest first, every one marked short-form (the playlist's rows are not).
     * @throws Exception the Shorts tab's failure when the playlist did not answer either.
     */
    suspend fun shorts(channelId: String): List<VideoItem> {
        try {
            return source.channelShorts(channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$channelId: the Shorts tab failed: ${e.logLine()}")
            val playlist = shortsPlaylistId(channelId)
            if (playlist == null || e.endsTheChain()) throw e
            return try {
                source.playlist(playlist).videos.ownedBy(channelId).map { it.copy(isShortForm = true) }
                    .also { Log.w(TAG, "$channelId: using the Shorts playlist instead (${it.size} Shorts) after: ${e.logLine()}") }
            } catch (second: CancellationException) {
                throw second
            } catch (second: Exception) {
                Log.w(TAG, "$channelId: the Shorts playlist failed: ${second.logLine()}")
                throw e
            }
        }
    }

    private suspend fun read(step: UploadsFrom, channelId: String, key: PageKey?): UploadsPage = when (step) {
        UploadsFrom.VIDEOS_TAB ->
            source.channelVideos(channelId, key).let { UploadsPage(it.items.filterIsInstance<VideoItem>().ownedBy(channelId), it.next, step) }
        UploadsFrom.LONG_FORM_PLAYLIST -> playlist(step, requireNotNull(longFormPlaylistId(channelId)), channelId, key)
        UploadsFrom.ALL_UPLOADS_PLAYLIST -> playlist(step, requireNotNull(allUploadsPlaylistId(channelId)), channelId, key)
        UploadsFrom.RSS ->
            if (key != null) {
                UploadsPage(emptyList(), null, step)
            } else {
                source.recentVideos(channelId).let { rows -> UploadsPage(rows.filterNot { it.isShortForm }.ownedBy(channelId), null, step) }
            }
    }

    private suspend fun playlist(step: UploadsFrom, playlistId: String, channelId: String, key: PageKey?) =
        source.playlist(playlistId, key).let { UploadsPage(it.videos.ownedBy(channelId), it.next, step) }

    /** The channel's own videos: a row that does not say whose it is (playlist rows may not) is the channel's, when that is known. */
    private fun List<VideoItem>.ownedBy(channelId: String): List<VideoItem> =
        if (isChannelId(channelId)) map { if (it.channelId == null) it.copy(channelId = channelId) else it } else this

    private val UploadsFrom.needsChannelId: Boolean
        get() = this == UploadsFrom.LONG_FORM_PLAYLIST || this == UploadsFrom.ALL_UPLOADS_PLAYLIST

    companion object {
        private const val TAG = "ChannelUploads"
        private val ENDS_THE_CHAIN = setOf(SourceErrorKind.NETWORK, SourceErrorKind.RATE_LIMITED, SourceErrorKind.ACCOUNT_TERMINATED)

        /** «UULF» + the channel id without its «UC»: the channel's long-form uploads; null for anything but a «UC…» id (a handle). */
        fun longFormPlaylistId(channelId: String): String? = uploadsPlaylist("UULF", channelId)

        /** «UU» + the channel id without its «UC»: every upload of the channel; null for anything but a «UC…» id. */
        fun allUploadsPlaylistId(channelId: String): String? = uploadsPlaylist("UU", channelId)

        /** «UUSH» + the channel id without its «UC»: the channel's Shorts; null for anything but a «UC…» id. */
        fun shortsPlaylistId(channelId: String): String? = uploadsPlaylist("UUSH", channelId)

        private fun uploadsPlaylist(prefix: String, channelId: String): String? = channelId.takeIf(::isChannelId)?.let { prefix + it.drop(2) }

        private fun Throwable.endsTheChain(): Boolean = this is SourceException && kind in ENDS_THE_CHAIN
    }
}

/**
 * A channel's videos page by page for [ChannelUploads.firstPage]'s chain: the first page comes from the first step that
 * answers, and every later page from the same list, whatever it was (the Videos tab's keys are not a playlist's).
 * One instance per channel page; the loader asks for one page at a time.
 */
class ChannelUploadsPager(private val uploads: ChannelUploads, private val channelId: String) {
    @Volatile
    private var from = UploadsFrom.VIDEOS_TAB

    /** The first page when [key] is null (the whole chain is asked again, a retry included), else the page after [key]. */
    suspend fun page(key: PageKey?): UploadsPage =
        if (key == null) uploads.firstPage(channelId).also { from = it.from } else uploads.nextPage(channelId, from, key)
}

/** A failure as one line of a log: the name and the text YouTube's answer gave ("ContentNotAvailableException: Got error: …"). */
internal fun Throwable.logLine(): String =
    if (this is SourceException) "$technicalName: $message" else "${javaClass.simpleName}: $message"

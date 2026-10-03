package com.ahmadnayfeh.shasha.data

import android.util.Log
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.CancellationException

/** Where a channel's uploads can be read from. The order they are tried in is decided by [ChannelUploads]. */
enum class UploadsFrom(internal val logName: String) {
    /**
     * The channel's Videos tab ([VideoSource.channelVideos]): every video with its duration, no Shorts, about 30 a page.
     * The usual way, and the only one the app used for a child's home and for a channel's page until a TV in Saudi
     * Arabia answered it with `ContentNotAvailableException` for every channel.
     */
    VIDEOS_TAB("Videos tab"),

    /**
     * «UULF…» ([ChannelUploads.longFormPlaylistId]): the playlist YouTube keeps of each channel's long-form uploads. Real
     * YouTube, checked on four kids channels (`RealNetworkKidsUploadsTest`): it is the Videos tab as a playlist, the same
     * videos in the same order, every one with its duration and no Shorts, 100 a page, so paging can go on from it.
     * Another request shape than the tab's (a playlist, not a channel tab), so it can work where the tab does not.
     */
    LONG_FORM_PLAYLIST("long-form uploads playlist"),

    /**
     * The channel's RSS feed ([VideoSource.recentVideos], which itself falls back to the Videos tab): the newest 15 to 30
     * uploads with exact dates and Shorts marked by their link, but **no durations**, and no next page.
     */
    RSS("RSS feed"),
}

/** One page of a channel's uploads. [next] continues the list [from] gave: hand both to [ChannelUploads.nextPage]. */
class UploadsPage(val videos: List<VideoItem>, val next: PageKey?, val from: UploadsFrom)

/** [ChannelUploads.newest] was told to stop between two steps (a video started playing): nothing failed, ask again later. */
class UploadsPaused : Exception("uploads paused")

/**
 * A channel's uploads, read through a chain of ways to get them instead of one, so that a way that fails on one
 * TV (`ContentNotAvailableException`: the channel-tab request answered with an error, whatever its text says) does not
 * leave a child's home or a channel's page empty.
 *
 * **The chain** (each step only when the ones before it failed or came back empty; every failure is logged with
 * `Log.w`, tag `ChannelUploads`, channel id and message, for `adb logcat`):
 * 1. [UploadsFrom.VIDEOS_TAB];
 * 2. [UploadsFrom.LONG_FORM_PLAYLIST];
 * 3. [UploadsFrom.RSS], only where the list does not need durations ([firstPage] with `rssLast`).
 *
 * A home feed ([newest]) for a profile that shows Shorts, which needs no durations, asks the cheapest first: RSS, then the
 * playlist (RSS already has the Videos tab behind it).
 *
 * **Why RSS is not a step where durations are needed (the decision on [com.ahmadnayfeh.shasha.policy.ContentFilter.needsDuration]).**
 * RSS has no durations, and a profile that hides Shorts counts every regular upload of three minutes or less (the
 * parent's limit) as a Short, which the player refuses once it has looked the video up. Kids' channels are full of them. So
 * `needsDuration` stays as it is: such a profile never shows a video whose duration is unknown, and RSS rows would all be
 * left out anyway. They are not fetched for it at all (a failing channel costs two requests, not three). The cards that
 * are shown are therefore always cards the player will accept; the price is that a TV on which every request for a
 * channel's own pages fails still has nothing to show such a profile, and says so with the unified error (and the log).
 * Where Shorts are shown (and for the adult's channel page, which does not hide them) no duration is needed and RSS
 * is the last step.
 *
 * **Not a step: «UU…» (every upload).** Its rows carry durations, but never the Shorts flag (real YouTube: 70 of the first
 * 100 uploads of a Shorts-heavy channel last 60 seconds or less and none is flagged), so it cannot be told from the
 * Videos tab's list and would put Shorts on a channel's page. [UploadsFrom.LONG_FORM_PLAYLIST] is the Videos tab's twin.
 *
 * **Which error is thrown** when no step answered: the first one, the Videos tab's (what the error screen has always
 * shown). A step that answered with an empty list is an answer, not a failure: a channel without long-form videos
 * (only Shorts) has an empty Videos tab and may have no «UULF…» playlist at all; it shows none, as before.
 *
 * Three failures end the chain, because the next step would only repeat them: a connectivity failure
 * ([SourceErrorKind.NETWORK]: offline, DNS, timeout; the next step would wait for the same timeout, 10 s to connect and 30 s
 * to read, for nothing), a rate limit ([SourceErrorKind.RATE_LIMITED], HTTP 429: asking again at once only prolongs it), and
 * a terminated channel ([SourceErrorKind.ACCOUNT_TERMINATED]: it is gone, and every home visit would ask again).
 */
class ChannelUploads(private val source: VideoSource) {

    /**
     * A channel's newest uploads for a home feed (a child's home, [com.ahmadnayfeh.shasha.ui.screens.kids.KidsFeed]).
     *
     * [withDurations]: the profile hides Shorts, so every video needs its duration: the Videos tab, then the long-form
     * uploads playlist (no RSS, see above). Otherwise the cheapest first, RSS (the Videos tab behind it), then the playlist.
     * [proceed] is asked before each step after the first: false throws [UploadsPaused] (nothing is started while a video
     * plays). From the playlist, whose page holds 100, only the newest [NEWEST_PER_CHANNEL]: a feed that mixes the channels would
     * otherwise be filled by the one that uploads most, leaving a quieter channel's newest videos out of it altogether (real
     * YouTube: four kids channels, the home's 120 newest left one of them out).
     * @throws SourceException when no step answered.
     */
    suspend fun newest(channelId: String, withDurations: Boolean, proceed: () -> Boolean = { true }): UploadsPage {
        val steps = if (withDurations) listOf(UploadsFrom.VIDEOS_TAB, UploadsFrom.LONG_FORM_PLAYLIST) else listOf(UploadsFrom.RSS, UploadsFrom.LONG_FORM_PLAYLIST)
        val page = first(channelId, steps, rssKeepsShorts = true, proceed)
        return if (page.from == UploadsFrom.LONG_FORM_PLAYLIST && page.videos.size > NEWEST_PER_CHANNEL) UploadsPage(page.videos.take(NEWEST_PER_CHANNEL), page.next, page.from) else page
    }

    /**
     * The first page of a channel's own videos page (the Videos tab's list, so no Shorts: they have their own row).
     * [rssLast]: RSS follows the playlist, for a profile that needs no durations (it shows Shorts); the feed's Shorts, marked
     * by their link, are left out. Its single page has no next. Paging goes on with [nextPage] from [UploadsPage.from].
     * @throws SourceException when no step answered.
     */
    suspend fun firstPage(channelId: String, rssLast: Boolean, tabTried: Boolean = false): UploadsPage {
        // [tabTried]: the caller already asked the Videos tab for this page, and it failed; it is not asked twice.
        val steps = listOfNotNull(UploadsFrom.VIDEOS_TAB.takeUnless { tabTried }, UploadsFrom.LONG_FORM_PLAYLIST, UploadsFrom.RSS.takeIf { rssLast })
        return first(channelId, steps, rssKeepsShorts = false, proceed = { true })
    }

    /** The page after [key], from the list [from] gave the page before (RSS has one page only). */
    suspend fun nextPage(channelId: String, from: UploadsFrom, key: PageKey): UploadsPage = when (from) {
        UploadsFrom.VIDEOS_TAB -> source.channelVideos(channelId, key).let { UploadsPage(it.items.filterIsInstance<VideoItem>().ownedBy(channelId), it.next, from) }
        UploadsFrom.LONG_FORM_PLAYLIST -> source.playlist(requireNotNull(longFormPlaylistId(channelId)), key).let { UploadsPage(it.videos.ownedBy(channelId), it.next, from) }
        UploadsFrom.RSS -> UploadsPage(emptyList(), null, from)
    }

    private suspend fun first(channelId: String, steps: List<UploadsFrom>, rssKeepsShorts: Boolean, proceed: () -> Boolean): UploadsPage {
        var firstError: SourceException? = null
        var emptyAnswer: UploadsPage? = null
        var tried = 0
        for (step in steps) {
            if (step == UploadsFrom.LONG_FORM_PLAYLIST && longFormPlaylistId(channelId) == null) continue // a handle has no such playlist
            if (tried++ > 0 && !proceed()) throw UploadsPaused()
            try {
                val page = read(step, channelId, rssKeepsShorts)
                if (page.videos.isNotEmpty()) {
                    firstError?.let { Log.i(TAG, "$channelId: ${page.videos.size} uploads from the ${step.logName} after: ${it.technicalName}: ${it.message}") }
                    return page
                }
                emptyAnswer = emptyAnswer ?: page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.asSourceException()
                val text = "$channelId: the ${step.logName} failed: ${error.technicalName}: ${error.message}"
                // After a step that answered "no videos" this is what a channel without long-form videos looks like: not a warning.
                if (emptyAnswer == null) Log.w(TAG, text) else Log.i(TAG, text)
                firstError = firstError ?: error
                if (error.kind in ENDS_THE_CHAIN) break
            }
        }
        emptyAnswer?.let { return it }
        throw firstError ?: SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NoUploadsSource", "No way to list the uploads of $channelId", null)
    }

    private suspend fun read(step: UploadsFrom, channelId: String, rssKeepsShorts: Boolean): UploadsPage = when (step) {
        UploadsFrom.VIDEOS_TAB -> source.channelVideos(channelId).let { UploadsPage(it.items.filterIsInstance<VideoItem>().ownedBy(channelId), it.next, step) }
        UploadsFrom.LONG_FORM_PLAYLIST -> source.playlist(requireNotNull(longFormPlaylistId(channelId))).let { UploadsPage(it.videos.ownedBy(channelId), it.next, step) }
        UploadsFrom.RSS -> source.recentVideos(channelId).let { rows ->
            UploadsPage((if (rssKeepsShorts) rows else rows.filterNot { it.isShortForm }).ownedBy(channelId), null, step)
        }
    }

    /** The channel's own uploads: a row that does not say whose it is (playlist rows may not) is the channel's. */
    private fun List<VideoItem>.ownedBy(channelId: String): List<VideoItem> = map { if (it.channelId == null) it.copy(channelId = channelId) else it }

    companion object {
        private const val TAG = "ChannelUploads"

        /** What a channel brings to a feed that mixes channels ([newest]) from the playlist: the size of the Videos tab's first page. */
        const val NEWEST_PER_CHANNEL = 30
        private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
        private val ENDS_THE_CHAIN = setOf(SourceErrorKind.NETWORK, SourceErrorKind.RATE_LIMITED, SourceErrorKind.ACCOUNT_TERMINATED)

        /** «UULF…» + the channel id without its «UC»: the channel's long-form uploads; null for anything but a «UC…» id (a handle). */
        fun longFormPlaylistId(channelId: String): String? = channelId.takeIf(CHANNEL_ID::matches)?.let { "UULF" + it.drop(2) }
    }
}

/**
 * A channel's videos page by page for [ChannelUploads.firstPage]'s chain: the first page comes from the first step that
 * answers, and every later page from the same list, whatever it was (the Videos tab's keys are not the playlist's).
 * One instance per channel page; the loader asks for one page at a time.
 */
class ChannelUploadsPager(
    private val uploads: ChannelUploads,
    private val channelId: String,
    /** Asked when the first page is read: RSS may follow the playlist (the profile shows Shorts, so needs no durations). */
    private val rssLast: () -> Boolean = { false },
) {
    @Volatile
    private var from = UploadsFrom.VIDEOS_TAB

    /** [tabTried]: the caller already asked the Videos tab for the first page (and it failed): start from the playlist. */
    suspend fun page(key: PageKey?, tabTried: Boolean = false): UploadsPage =
        if (key == null) uploads.firstPage(channelId, rssLast(), tabTried).also { from = it.from } else uploads.nextPage(channelId, from, key)
}

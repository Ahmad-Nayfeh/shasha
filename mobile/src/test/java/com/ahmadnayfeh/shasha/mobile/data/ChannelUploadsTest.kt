package com.ahmadnayfeh.shasha.mobile.data

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.ScriptedChannelSource
import com.ahmadnayfeh.shasha.mobile.ScriptedChannelSource.Companion.contentUnavailable
import com.ahmadnayfeh.shasha.mobile.ScriptedChannelSource.Companion.offline
import com.ahmadnayfeh.shasha.mobile.channelId
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The chain a channel's videos are read through: the Videos tab, then the long-form uploads playlist («UULF…»), then the
 * playlist of every upload («UU…»), then RSS. One way failing does not fail the channel; every failure is logged with the
 * channel's id and what YouTube said; paging goes on from the list that answered. The same for the Shorts (the tab, then
 * «UUSH…»). The ways are scripted one by one ([ScriptedChannelSource]), so each can fail alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ChannelUploadsTest {
    private val source = ScriptedChannelSource()
    private val uploads = ChannelUploads(source)
    private val channel = channelId(1)
    private val longForm = "UULF" + channel.drop(2)
    private val everyUpload = "UU" + channel.drop(2)
    private val shortsList = "UUSH" + channel.drop(2)

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    private fun logs(priority: Int? = null) = ShadowLog.getLogsForTag("ChannelUploads").filter { priority == null || it.type == priority }

    private fun v(id: String, short: Boolean = false, channel: String? = this.channel) =
        video(id, channelId = channel, seconds = if (short) null else 600).copy(isShortForm = short)

    // ---- the ids of the playlists YouTube keeps for a channel ----------------------------------------------------------

    @Test
    fun thePlaylistsOfAChannelAreItsIdWithAnotherPrefix() {
        val id = "UCozAuGJZ5MPoijz8IE_1Gtw"

        assertThat(ChannelUploads.longFormPlaylistId(id)).isEqualTo("UULFozAuGJZ5MPoijz8IE_1Gtw")
        assertThat(ChannelUploads.allUploadsPlaylistId(id)).isEqualTo("UUozAuGJZ5MPoijz8IE_1Gtw")
        assertThat(ChannelUploads.shortsPlaylistId(id)).isEqualTo("UUSHozAuGJZ5MPoijz8IE_1Gtw")
    }

    @Test
    fun onlyARealChannelIdHasPlaylistsAHandleOrAShortIdHasNone() {
        for (notAnId in listOf("@MrBeast", "c/name", "user/name", "UCtooshort", "")) {
            assertThat(ChannelUploads.longFormPlaylistId(notAnId)).isNull()
            assertThat(ChannelUploads.allUploadsPlaylistId(notAnId)).isNull()
            assertThat(ChannelUploads.shortsPlaylistId(notAnId)).isNull()
            assertThat(isChannelId(notAnId)).isFalse()
        }
        assertThat(isChannelId(channel)).isTrue()
    }

    // ---- the videos ------------------------------------------------------------------------------------------------------

    @Test
    fun theVideosTabAnswersNothingElseIsAskedAndNothingIsLogged() = runBlocking<Unit> {
        source.tab(channel, listOf(v("tab00000001")), next = "p2")

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        assertThat(page.videos.map { it.videoId }).containsExactly("tab00000001")
        assertThat(page.next).isEqualTo(PageKey("p2"))
        assertThat(source.calls).containsExactly("tab:$channel")
        assertThat(logs()).isEmpty()
    }

    @Test
    fun whenTheVideosTabFailsTheLongFormPlaylistAnswersAndTheFailureIsLoggedWithWhatYouTubeSaid() = runBlocking<Unit> {
        // What a phone on mobile data in Saudi Arabia answered the channel tab with: the extractor's plain ContentNotAvailableException.
        source.tabFails(channel, contentUnavailable("Got error: \"Precondition check failed.\""))
        source.playlist(longForm, listOf(v("list0000001"), v("list0000002")), next = "more")

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(page.videos.map { it.videoId }).containsExactly("list0000001", "list0000002").inOrder()
        assertThat(page.next).isEqualTo(PageKey("more"))
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
        // The failure is in the log with the channel and the message, for adb logcat; then the way that answered instead.
        val warnings = logs(Log.WARN).map { it.msg }
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains(channel)
        assertThat(warnings[0]).contains("Videos tab")
        assertThat(warnings[0]).contains("ContentNotAvailableException")
        assertThat(warnings[0]).contains("Precondition check failed")
        assertThat(warnings[1]).contains("$channel: using the long-form uploads playlist instead (2 videos)")
        assertThat(warnings[1]).contains("Precondition check failed")
    }

    @Test
    fun whenTheLongFormPlaylistFailsTooTheUploadsPlaylistAnswers() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.playlist(everyUpload, listOf(v("all00000001")), next = "more")

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.ALL_UPLOADS_PLAYLIST)
        assertThat(page.videos.map { it.videoId }).containsExactly("all00000001")
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm", "playlist:$everyUpload").inOrder()
        // The two failures, and the uploads playlist that answered instead.
        assertThat(logs(Log.WARN)).hasSize(3)
        assertThat(logs(Log.WARN).last().msg).contains("using the uploads playlist instead (1 videos)")
    }

    @Test
    fun whenEveryPlaylistFailsTooRssAnswersWithoutItsShortsAndWithNoNextPage() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.playlistFails(everyUpload)
        source.feed(channel, listOf(v("rss00000001"), v("rss00000002", short = true), v("rss00000003")))

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.RSS)
        assertThat(page.videos.map { it.videoId }).containsExactly("rss00000001", "rss00000003").inOrder()
        assertThat(page.next).isNull()
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm", "playlist:$everyUpload", "rss:$channel").inOrder()
        // RSS has one page only.
        assertThat(uploads.nextPage(channel, UploadsFrom.RSS, PageKey("any")).videos).isEmpty()
        assertThat(source.calls).hasSize(4)
    }

    @Test
    fun whenEveryWayFailsTheVideosTabsErrorIsThrownAndEachFailureIsLogged() {
        source.tabFails(channel, contentUnavailable("Got error: \"first\""))
        source.playlistFails(longForm)
        source.playlistFails(everyUpload)
        source.feedFails(channel)

        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.firstPage(channel) } }

        // The first one: what the page has always shown for a channel it cannot read.
        assertThat(error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(error.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(error.message).contains("first")
        val warnings = logs(Log.WARN).map { it.msg }
        assertThat(warnings).hasSize(4)
        assertThat(warnings[0]).contains("$channel: the Videos tab failed")
        assertThat(warnings[1]).contains("$channel: the long-form uploads playlist failed")
        assertThat(warnings[1]).contains("The playlist does not exist")
        assertThat(warnings[2]).contains("$channel: the uploads playlist failed")
        assertThat(warnings[3]).contains("$channel: the RSS feed failed")
    }

    @Test
    fun aVideosTabWithNoVideosIsNotTheEndTheNextWayIsAsked() = runBlocking<Unit> {
        source.tab(channel, emptyList())
        source.playlist(longForm, listOf(v("list0000001")))

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
    }

    @Test
    fun anEmptyPageWithANextOneIsTheListNothingElseIsAsked() = runBlocking<Unit> {
        // The built-in content filter may hold a whole page back and keep its next-page key: the list goes on, from the same way.
        source.tab(channel, emptyList(), next = "t2")
        source.playlist(longForm, listOf(v("list0000001")))

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        assertThat(page.videos).isEmpty()
        assertThat(page.next).isEqualTo(PageKey("t2"))
        assertThat(source.calls).containsExactly("tab:$channel")
    }

    @Test
    fun aChannelWithOnlyShortsHasNoLongFormPlaylistTheUploadsPlaylistHasItsVideos() = runBlocking<Unit> {
        source.tab(channel, emptyList())
        source.playlistFails(longForm)
        source.playlist(everyUpload, listOf(v("short000001")))

        val page = uploads.firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.ALL_UPLOADS_PLAYLIST)
        assertThat(page.videos.map { it.videoId }).containsExactly("short000001")
        // After an empty answer a missing playlist is what such a channel looks like: not a warning.
        assertThat(logs(Log.WARN)).isEmpty()
    }

    @Test
    fun aChannelWithNoVideosAnywhereAnswersEmptyNotWithAnError() = runBlocking<Unit> {
        source.tab(channel, emptyList())
        source.playlistFails(longForm)
        source.playlistFails(everyUpload)
        source.feed(channel, emptyList())

        val page = uploads.firstPage(channel)

        assertThat(page.videos).isEmpty()
        assertThat(page.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        assertThat(logs(Log.WARN)).isEmpty()
    }

    @Test
    fun aConnectivityFailureEndsTheChainTheNextWayWouldWaitForTheSameTimeout() {
        source.tabFails(channel, offline())
        source.playlist(longForm, listOf(v("list0000001")))

        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.firstPage(channel) } }

        assertThat(error.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(source.calls).containsExactly("tab:$channel")
        assertThat(logs(Log.WARN)).hasSize(1)
    }

    @Test
    fun aRateLimitEndsTheChainTooAskingAgainAtOnceOnlyProlongsIt() {
        source.tabFails(channel, SourceException(SourceErrorKind.RATE_LIMITED, "ReCaptchaException", "reCaptcha Challenge requested (HTTP 429)", null))
        source.playlist(longForm, listOf(v("list0000001")))

        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.firstPage(channel) } }

        assertThat(error.kind).isEqualTo(SourceErrorKind.RATE_LIMITED)
        assertThat(source.calls).containsExactly("tab:$channel")
    }

    @Test
    fun aTerminatedChannelEndsTheChainItIsGoneTheOtherWaysWouldSayTheSame() {
        source.tabFails(channel, SourceException(SourceErrorKind.ACCOUNT_TERMINATED, "AccountTerminatedException", "This account has been terminated", null))
        source.playlist(longForm, listOf(v("list0000001")))

        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.firstPage(channel) } }

        assertThat(error.kind).isEqualTo(SourceErrorKind.ACCOUNT_TERMINATED)
        assertThat(source.calls).containsExactly("tab:$channel")
    }

    @Test
    fun anExceptionThatIsNotASourceExceptionIsAFailureOfThatWayOnly() = runBlocking<Unit> {
        // A bug or an unexpected class in one way must not take the chain down with it.
        val flaky = object : VideoSource by source {
            override suspend fun channelVideos(channelId: String, page: PageKey?) = throw IllegalStateException("boom")
        }
        source.playlist(longForm, listOf(v("list0000001")))

        val page = ChannelUploads(flaky).firstPage(channel)

        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(logs(Log.WARN).first().msg).contains("IllegalStateException: boom")
    }

    @Test
    fun aCancellationIsNotAFailureItPropagates() {
        val cancelling = object : VideoSource by source {
            override suspend fun channelVideos(channelId: String, page: PageKey?) = throw CancellationException("left the screen")
        }

        assertThrows(CancellationException::class.java) { runBlocking { ChannelUploads(cancelling).firstPage(channel) } }

        assertThat(source.calls).isEmpty()
        assertThat(logs()).isEmpty()
    }

    @Test
    fun aHandleHasNoPlaylistsTheChainHasTwoWaysLess() = runBlocking<Unit> {
        source.tabFails("@kids")
        source.feed("@kids", listOf(v("rss00000001", channel = null)))

        val page = uploads.firstPage("@kids")

        assertThat(page.from).isEqualTo(UploadsFrom.RSS)
        assertThat(source.calls).containsExactly("tab:@kids", "rss:@kids").inOrder()
        // A row that says nothing about its channel stays so: a handle is no channel id to put in it.
        assertThat(page.videos.single().channelId).isNull()
    }

    @Test
    fun rowsThatDoNotSayWhoseTheyAreAreTheChannels() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001", channel = null), v("list0000002", channel = "UCsomeoneelse000000000")))

        val page = uploads.firstPage(channel)

        assertThat(page.videos.map { it.channelId }).containsExactly(channel, "UCsomeoneelse000000000").inOrder()
    }

    // ---- paging goes on from the list that answered ---------------------------------------------------------------------

    @Test
    fun theVideosTabPagesThroughItsOwnKeys() = runBlocking<Unit> {
        source.tab(channel, listOf(v("tab00000001")), next = "t2")
        source.tab(channel, listOf(v("tab00000002")), key = "t2")
        val pager = ChannelUploadsPager(uploads, channel)

        val first = pager.page(null)
        val second = pager.page(first.next)

        assertThat(second.videos.map { it.videoId }).containsExactly("tab00000002")
        assertThat(second.next).isNull()
        assertThat(source.calls).containsExactly("tab:$channel", "tab:$channel:t2").inOrder()
    }

    @Test
    fun whenTheTabFailsPagingContinuesFromThePlaylistNeverFromTheTab() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001"), v("list0000002")), next = "k1")
        source.playlist(longForm, listOf(v("list0000003")), next = "k2", key = "k1")
        source.playlist(longForm, listOf(v("list0000004")), key = "k2")
        val pager = ChannelUploadsPager(uploads, channel)

        val pages = mutableListOf(pager.page(null))
        while (true) pages += pager.page(pages.last().next ?: break)

        assertThat(pages.flatMap { it.videos }.map { it.videoId }).containsExactly("list0000001", "list0000002", "list0000003", "list0000004").inOrder()
        assertThat(pages.map { it.from }.toSet()).containsExactly(UploadsFrom.LONG_FORM_PLAYLIST)
        // The tab is asked once (and failed): its keys are not the playlist's, so no later page goes there.
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm", "playlist:$longForm:k1", "playlist:$longForm:k2").inOrder()
    }

    @Test
    fun startingOverAsksTheChainAgainARetryFindsTheTabBack() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001")), next = "k1")
        val pager = ChannelUploadsPager(uploads, channel)
        pager.page(null)
        // The tab recovers, and the page is loaded again (a retry): the first page comes from the tab, and so does the next.
        source.tab(channel, listOf(v("tab00000001")), next = "t2")
        source.tab(channel, listOf(v("tab00000002")), key = "t2")

        val again = pager.page(null)

        assertThat(again.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        assertThat(pager.page(again.next).videos.map { it.videoId }).containsExactly("tab00000002")
    }

    // ---- the Shorts -----------------------------------------------------------------------------------------------------

    @Test
    fun theShortsTabAnswersNothingElseIsAsked() = runBlocking<Unit> {
        source.shortsTab(channel, listOf(v("short000001", short = true)))

        assertThat(uploads.shorts(channel).map { it.videoId }).containsExactly("short000001")
        assertThat(source.calls).containsExactly("shorts:$channel")
        assertThat(logs()).isEmpty()
    }

    @Test
    fun whenTheShortsTabFailsTheShortsPlaylistAnswersAndItsRowsAreMarkedAsShorts() = runBlocking<Unit> {
        source.shortsTabFails(channel, contentUnavailable("Got error: \"Precondition check failed.\""))
        source.playlist(shortsList, listOf(v("short000001"), v("short000002", channel = null)))

        val shorts = uploads.shorts(channel)

        assertThat(shorts.map { it.videoId }).containsExactly("short000001", "short000002").inOrder()
        assertThat(shorts.all { it.isShortForm }).isTrue()
        assertThat(shorts.map { it.channelId }.toSet()).containsExactly(channel)
        assertThat(source.calls).containsExactly("shorts:$channel", "playlist:$shortsList").inOrder()
        val warnings = logs(Log.WARN).map { it.msg }
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("$channel: the Shorts tab failed: ContentNotAvailableException: Got error")
        assertThat(warnings[1]).contains("$channel: using the Shorts playlist instead (2 Shorts)")
    }

    @Test
    fun whenTheShortsPlaylistFailsTooTheTabsErrorIsThrown() {
        source.shortsTabFails(channel, contentUnavailable("Got error: \"first\""))
        source.playlistFails(shortsList)

        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.shorts(channel) } }

        assertThat(error.message).contains("first")
        assertThat(logs(Log.WARN).map { it.msg }).hasSize(2)
    }

    @Test
    fun theShortsOfAConnectivityFailureAreNotAskedForTwiceAndAHandleHasNoShortsPlaylist() {
        source.shortsTabFails(channel, offline())
        source.shortsTabFails("@kids")

        assertThrows(SourceException::class.java) { runBlocking { uploads.shorts(channel) } }
        assertThrows(SourceException::class.java) { runBlocking { uploads.shorts("@kids") } }

        assertThat(source.calls).containsExactly("shorts:$channel", "shorts:@kids")
    }

    @Test
    fun aShortsPlaylistWithNoVideosIsAnAnswerNoShorts() = runBlocking<Unit> {
        source.shortsTabFails(channel)
        source.playlist(shortsList, emptyList())

        assertThat(uploads.shorts(channel)).isEmpty()
    }
}

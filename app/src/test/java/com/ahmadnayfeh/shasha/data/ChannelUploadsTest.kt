package com.ahmadnayfeh.shasha.data

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource.Companion.contentUnavailable
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource.Companion.offline
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
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
 * The chain a channel's uploads are read through (a child's home, a channel's page): the Videos tab, then the long-form
 * uploads playlist (UULF, the same list with the same durations), then, only where no duration is needed, RSS. One way
 * failing does not fail the channel; every failure is logged with the channel's id; paging goes on from the list that
 * answered. The ways are scripted one by one (ScriptedChannelSource), so each can fail alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ChannelUploadsTest {
    private val source = ScriptedChannelSource()
    private val uploads = ChannelUploads(source)
    private val channel = channelId(1)
    private val longForm = "UULF" + channel.drop(2)

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    private fun logs(priority: Int? = null) = ShadowLog.getLogsForTag("ChannelUploads").filter { priority == null || it.type == priority }

    private fun v(id: String, seconds: Long? = 600, short: Boolean = false) = video(id, channel, NOW_MS, durationSeconds = seconds, short = short)

    @Test
    fun theLongFormPlaylistIdIsTheChannelsWithUULF() {
        assertThat(ChannelUploads.longFormPlaylistId("UCozAuGJZ5MPoijz8IE_1Gtw")).isEqualTo("UULFozAuGJZ5MPoijz8IE_1Gtw")
        // Only a channel id has one: a handle does not.
        assertThat(ChannelUploads.longFormPlaylistId("@MrBeast")).isNull()
        assertThat(ChannelUploads.longFormPlaylistId("UCtooshort")).isNull()
    }

    @Test
    fun theVideosTabAnswers_nothingElseIsAsked_andNothingIsLogged() = runBlocking<Unit> {
        source.tab(channel, listOf(v("tab00000001")), next = "p2")
        val page = uploads.newest(channel, withDurations = true)
        assertThat(page.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        assertThat(page.videos.map { it.videoId }).containsExactly("tab00000001")
        assertThat(page.next).isEqualTo(PageKey("p2"))
        assertThat(source.calls).containsExactly("tab:$channel")
        assertThat(logs()).isEmpty()
    }

    @Test
    fun whenTheVideosTabFails_theLongFormPlaylistAnswers_withItsDurations_andTheFailureIsLogged() = runBlocking<Unit> {
        // What a TV in Saudi Arabia answered the channel tab with: the extractor's plain ContentNotAvailableException.
        source.tabFails(channel, contentUnavailable("Got error: \"Precondition check failed.\""))
        source.playlist(longForm, listOf(v("list0000001", 600), v("list0000002", 150)), next = "more")
        val page = uploads.newest(channel, withDurations = true)
        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(page.videos.map { it.videoId to it.durationSeconds }).containsExactly("list0000001" to 600L, "list0000002" to 150L).inOrder()
        assertThat(page.next).isEqualTo(PageKey("more"))
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()

        // The failure is in the log with the channel and the message, for adb logcat; the way that worked is named.
        val warning = logs(Log.WARN).single().msg
        assertThat(warning).contains(channel)
        assertThat(warning).contains("ContentNotAvailableException")
        assertThat(warning).contains("Precondition check failed")
        assertThat(logs(Log.INFO).single().msg).contains("2 uploads from the long-form uploads playlist")
    }

    @Test
    fun aFeedGetsAChannelsNewestThirty_whicheverWayAnswered_aChannelsPageGetsItsPageWhole() = runBlocking<Unit> {
        // The playlist's page holds 100 videos, the Videos tab's about 30: a feed that mixes channels (the child's home keeps the newest
        // 120) must not be filled by the channel that uploads most. A channel's own page takes the whole page.
        source.tabFails(channel)
        source.playlist(longForm, (1..100).map { v("list" + it.toString().padStart(7, '0')) }, next = "more")
        val feed = uploads.newest(channel, withDurations = true)
        assertThat(feed.videos.map { it.videoId }).isEqualTo((1..ChannelUploads.NEWEST_PER_CHANNEL).map { "list" + it.toString().padStart(7, '0') })
        assertThat(feed.next).isEqualTo(PageKey("more"))
        assertThat(uploads.firstPage(channel, rssLast = false).videos).hasSize(100)
    }

    @Test
    fun rowsThatDoNotSayWhoseTheyAre_areTheChannels() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001").copy(channelId = null)))
        assertThat(uploads.newest(channel, withDurations = true).videos.single().channelId).isEqualTo(channel)
    }

    @Test
    fun aVideosTabThatAnswersWithNoVideos_isNotTheEnd_theNextWayIsAsked() = runBlocking<Unit> {
        source.tab(channel, emptyList())
        source.playlist(longForm, listOf(v("list0000001")))
        val page = uploads.newest(channel, withDurations = true)
        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
    }

    @Test
    fun aChannelWithoutLongFormVideos_answersEmpty_notWithAnError() = runBlocking<Unit> {
        // Only Shorts: its tab is empty and it has no UULF playlist ("The playlist does not exist."): no videos, no failure.
        source.tab(channel, emptyList())
        source.playlistFails(longForm)
        val page = uploads.newest(channel, withDurations = true)
        assertThat(page.videos).isEmpty()
        assertThat(page.from).isEqualTo(UploadsFrom.VIDEOS_TAB)
        // Expected for such a channel: not a warning.
        assertThat(logs(Log.WARN)).isEmpty()
    }

    @Test
    fun whenEveryWayFails_theFirstErrorIsThrown_andEachFailureIsLogged() {
        source.tabFails(channel, contentUnavailable("Got error: \"first\""))
        source.playlistFails(longForm)
        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.newest(channel, withDurations = true) } }
        // The Videos tab's: what the error screen has always shown.
        assertThat(error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(error.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(error.message).contains("first")
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
        val warnings = logs(Log.WARN).map { it.msg }
        assertThat(warnings).hasSize(2)
        assertThat(warnings[0]).contains("$channel: the Videos tab failed")
        assertThat(warnings[1]).contains("$channel: the long-form uploads playlist failed")
        assertThat(warnings[1]).contains("The playlist does not exist")
    }

    @Test
    fun aConnectivityFailureEndsTheChain_theNextWayWouldWaitForTheSameTimeout() {
        source.tabFails(channel, offline())
        source.playlist(longForm, listOf(v("list0000001")))
        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.newest(channel, withDurations = true) } }
        assertThat(error.kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(source.calls).containsExactly("tab:$channel")
        assertThat(logs(Log.WARN)).hasSize(1)
    }

    @Test
    fun aRateLimitEndsTheChainToo_askingAgainAtOnceOnlyProlongsIt() {
        source.tabFails(channel, SourceException(SourceErrorKind.RATE_LIMITED, "ReCaptchaException", "reCaptcha Challenge requested (HTTP 429)", null))
        source.playlist(longForm, listOf(v("list0000001")))
        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.newest(channel, withDurations = true) } }
        assertThat(error.kind).isEqualTo(SourceErrorKind.RATE_LIMITED)
        assertThat(source.calls).containsExactly("tab:$channel")
    }

    @Test
    fun aTerminatedChannelEndsTheChain_itIsGone_theOtherWaysWouldSayTheSame() {
        source.tabFails(channel, SourceException(SourceErrorKind.ACCOUNT_TERMINATED, "AccountTerminatedException", "This account has been terminated", null))
        source.playlist(longForm, listOf(v("list0000001")))
        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.firstPage(channel, rssLast = true) } }
        assertThat(error.kind).isEqualTo(SourceErrorKind.ACCOUNT_TERMINATED)
        assertThat(source.calls).containsExactly("tab:$channel")
    }

    @Test
    fun anExceptionThatIsNotASourceException_isAFailureOfThatWayOnly() = runBlocking<Unit> {
        // A bug or an unexpected class in one way must not take the chain down with it.
        val flaky = object : com.ahmadnayfeh.shasha.youtube.VideoSource by source {
            override suspend fun channelVideos(channelId: String, page: PageKey?) = throw IllegalStateException("boom")
        }
        source.playlist(longForm, listOf(v("list0000001")))
        val page = ChannelUploads(flaky).newest(channel, withDurations = true)
        assertThat(page.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(logs(Log.WARN).single().msg).contains("boom")
    }

    @Test
    fun aCancellationIsNotAFailure_itPropagates() {
        val cancelling = object : com.ahmadnayfeh.shasha.youtube.VideoSource by source {
            override suspend fun channelVideos(channelId: String, page: PageKey?) = throw CancellationException("left the screen")
        }
        assertThrows(CancellationException::class.java) { runBlocking { ChannelUploads(cancelling).newest(channel, withDurations = true) } }
        assertThat(source.calls).isEmpty()
        assertThat(logs()).isEmpty()
    }

    @Test
    fun aHandleHasNoPlaylist_theChainHasOneWayLess() {
        source.tabFails("@kids")
        val error = assertThrows(SourceException::class.java) { runBlocking { uploads.newest("@kids", withDurations = true) } }
        assertThat(error.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(source.calls).containsExactly("tab:@kids")
    }

    // ---- RSS: where it is a way, and where it is not --------------------------------------------------------------------

    @Test
    fun whereDurationsAreNeeded_rssIsNotAskedForAtAll() {
        // A profile that hides Shorts: RSS rows have no duration, the shorts rule cannot judge them, and the player would refuse every
        // regular upload of three minutes or less once it looked it up. So they would all be left out: they are not even fetched.
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.feed(channel, listOf(v("rss00000001", seconds = null)))
        assertThrows(SourceException::class.java) { runBlocking { uploads.newest(channel, withDurations = true) } }
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
    }

    @Test
    fun whereNoDurationIsNeeded_rssComesFirst_thePlaylistFollows() = runBlocking<Unit> {
        source.feed(channel, listOf(v("rss00000001", seconds = null), v("rss00000002", seconds = null, short = true)))
        // RSS answers: nothing else is asked, and the feed's Shorts stay (this profile shows them).
        val first = uploads.newest(channel, withDurations = false)
        assertThat(first.from).isEqualTo(UploadsFrom.RSS)
        assertThat(first.videos.map { it.videoId }).containsExactly("rss00000001", "rss00000002").inOrder()
        assertThat(first.next).isNull()
        assertThat(source.calls).containsExactly("rss:$channel")

        // RSS failing (its 404 windows) hands over to the playlist.
        source.calls.clear()
        source.feedFails(channel)
        source.playlist(longForm, listOf(v("list0000001")))
        val second = uploads.newest(channel, withDurations = false)
        assertThat(second.from).isEqualTo(UploadsFrom.LONG_FORM_PLAYLIST)
        assertThat(source.calls).containsExactly("rss:$channel", "playlist:$longForm").inOrder()
    }

    // ---- A channel's page: the first page, and paging from the list that answered -----------------------------------------

    @Test
    fun aChannelsPage_firstFromTheTab_thenFromTheTab() = runBlocking<Unit> {
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
    fun aChannelsPage_whenTheTabFails_pagingContinuesFromThePlaylist() = runBlocking<Unit> {
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
    fun aChannelsPage_startingOverAsksTheChainAgain() = runBlocking<Unit> {
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

    @Test
    fun aChannelsPage_rssIsTheLastWayOnlyWhereShortsShow_andItsShortsAreLeftOut() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlistFails(longForm)
        source.feed(channel, listOf(v("rss00000001", seconds = null), v("rss00000002", seconds = null, short = true)))

        // The adult's page shows Shorts (a row of their own): no duration is needed, RSS is the last way, without the feed's Shorts.
        val shown = ChannelUploadsPager(uploads, channel, rssLast = { true }).page(null)
        assertThat(shown.from).isEqualTo(UploadsFrom.RSS)
        assertThat(shown.videos.map { it.videoId }).containsExactly("rss00000001")
        assertThat(shown.next).isNull()

        // A profile that hides Shorts needs durations: no RSS, the error stands.
        source.calls.clear()
        assertThrows(SourceException::class.java) { runBlocking { ChannelUploadsPager(uploads, channel, rssLast = { false }).page(null) } }
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
    }

    // ---- Nothing starts while a video plays ---------------------------------------------------------------------------------

    @Test
    fun whenToldToStopBetweenTwoWays_nothingMoreIsAsked() {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001")))
        assertThrows(UploadsPaused::class.java) { runBlocking { uploads.newest(channel, withDurations = true, proceed = { false }) } }
        assertThat(source.calls).containsExactly("tab:$channel")
    }
}

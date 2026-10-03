package com.ahmadnayfeh.shasha.ui.screens.kids

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.NOW_MS
import com.ahmadnayfeh.shasha.data.ScriptedChannelSource
import com.ahmadnayfeh.shasha.data.channelId
import com.ahmadnayfeh.shasha.data.video
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What KidsFeed makes of the chain a channel's uploads are read through (ChannelUploads): a way failing does not fail the
 * channel, an answer is kept half an hour whichever way gave it, a channel nobody could answer is not kept (the next ask asks
 * again), and nothing more is asked once a video plays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class KidsFeedUploadsTest {
    private val source = ScriptedChannelSource()
    private val channel = channelId(1)
    private val longForm = "UULF" + channel.drop(2)
    private var now = NOW_MS
    private var playing = false

    private fun feed() = KidsFeed(source, clock = { now }, isPlaying = { playing }, lowPriority = { block -> block() })

    private fun v(id: String, seconds: Long? = 600) = video(id, channel, NOW_MS, durationSeconds = seconds)

    @Test
    fun aChannelWhoseVideosTabFails_isFilledFromThePlaylist_andKeptForHalfAnHour() = runBlocking<Unit> {
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001", 600), v("list0000002", 150)))
        val feed = feed()

        val first = feed.uploads(channel, withDurations = true)
        assertThat(first!!.map { it.videoId to it.durationSeconds }).containsExactly("list0000001" to 600L, "list0000002" to 150L).inOrder()
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm").inOrder()
        assertThat(feed.cachedUploads(channel, withDurations = true)).isEqualTo(first)

        // Fresh: no request. Half an hour later: asked again, the tab first.
        assertThat(feed.uploads(channel, withDurations = true)).isEqualTo(first)
        assertThat(source.calls).hasSize(2)
        now += KidsFeed.FRESH_MS + 1
        assertThat(feed.isStale(channel, withDurations = true)).isTrue()
        feed.uploads(channel, withDurations = true)
        assertThat(source.calls).containsExactly("tab:$channel", "playlist:$longForm", "tab:$channel", "playlist:$longForm").inOrder()
    }

    @Test
    fun whenNoWayAnswers_theFirstErrorIsThrown_nothingIsKept_andTheNextAskAsksAgain() {
        val feed = feed()
        val error = assertThrows(SourceException::class.java) { runBlocking { feed.uploads(channel, withDurations = true) } }
        assertThat(error.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(error.technicalName).isEqualTo("ContentNotAvailableException")
        assertThat(feed.cachedUploads(channel, withDurations = true)).isNull()
        assertThat(feed.isStale(channel, withDurations = true)).isTrue()

        // The tab is back: the next ask gets it.
        source.tab(channel, listOf(v("tab00000001")))
        assertThat(runBlocking { feed.uploads(channel, withDurations = true) }!!.map { it.videoId }).containsExactly("tab00000001")
    }

    @Test
    fun aVideoStartingBetweenTwoWays_stopsTheChain_andTheAnswerIsNull() = runBlocking<Unit> {
        // The Videos tab fails and, while it did, a video started: the playlist is not asked for. "Ask again later."
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("list0000001")))
        source.beforeTab = { playing = true }
        val feed = feed()
        assertThat(feed.uploads(channel, withDurations = true)).isNull()
        assertThat(source.calls).containsExactly("tab:$channel")
        assertThat(feed.cachedUploads(channel, withDurations = true)).isNull()

        // Nothing at all starts while it plays; once it stops, the chain runs from the top.
        source.calls.clear()
        assertThat(feed.uploads(channel, withDurations = true)).isNull()
        assertThat(source.calls).isEmpty()
        playing = false
        source.beforeTab = {}
        assertThat(feed.uploads(channel, withDurations = true)).isNotNull()
    }

    @Test
    fun forAProfileThatShowsShorts_rssIsFirst_andTheAnswerIsKeptApartFromTheOneWithDurations() = runBlocking<Unit> {
        source.feed(channel, listOf(v("rss00000001", seconds = null)))
        source.tab(channel, listOf(v("tab00000001")))
        val feed = feed()

        assertThat(feed.uploads(channel, withDurations = false)!!.map { it.videoId }).containsExactly("rss00000001")
        assertThat(source.calls).containsExactly("rss:$channel")
        // The list with durations is another one: asked for on its own.
        assertThat(feed.cachedUploads(channel, withDurations = true)).isNull()
        assertThat(feed.uploads(channel, withDurations = true)!!.map { it.videoId }).containsExactly("tab00000001")
    }
}

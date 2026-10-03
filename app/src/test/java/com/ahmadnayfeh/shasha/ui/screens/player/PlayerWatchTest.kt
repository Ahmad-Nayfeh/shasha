package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What playing a video records (SPEC §1): resume only between 10 s and 95 %, progress in order, the
 * history entry, "watched" at the end, watch later; and what follows the end (end card or next item).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerWatchTest {
    private lateinit var db: ShashaDatabase
    private lateinit var watch: WatchRepository

    /** Like the app's scope: writes start at once, in the order they are made. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val video = UiFixtures.videos[0]
    private val id = video.videoId

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDatabase()
        watch = WatchRepository(db, MutableProfile())
    }

    @After
    fun tearDown() {
        // The session's last writes (an end marks the video watched) finish before the database closes: a write
        // failing on a closed database would surface as an uncaught exception in whichever test runs next.
        settle()
        writes.coroutineContext.job.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun session() = WatchSession(id, watch, writes)

    private fun settle() = runBlocking { withTimeout(5_000) { writes.coroutineContext.job.children.toList().joinAll() } }

    @Test
    fun resumesOnlyPastTenSecondsAndBefore95Percent(): Unit = runBlocking {
        val cases = mapOf(8_000L to 0L, 11_000L to 11_000L, 300_000L to 300_000L, 580_000L to 0L, 600_000L to 0L)
        for ((saved, expected) in cases) {
            watch.saveProgress(id, saved, 600_000)
            assertThat(session().resumePositionMs()).isEqualTo(expected)
        }
        watch.clearProgress(id)
        assertThat(session().resumePositionMs()).isEqualTo(0)
    }

    @Test
    fun progressIsSavedInOrderAndLivesAreNotSaved() {
        val session = session()
        session.save(PlaybackProgress(100_000, 0, 600_000))
        session.save(PlaybackProgress(200_000, 0, 600_000))
        session.save(PlaybackProgress(310_000, 0, 600_000))
        // A live stream: Media3 reports its DVR window as the duration.
        session.save(PlaybackProgress(50_000, 0, 7_200_000, isLive = true))
        session.save(PlaybackProgress(60_000, 0, 0)) // duration not known yet
        settle()
        runBlocking {
            val saved = watch.progress(id)!!
            assertThat(saved.positionMs).isEqualTo(310_000)
            assertThat(saved.durationMs).isEqualTo(600_000)
        }
    }

    @Test
    fun aLiveStreamIsNeitherSavedNorMarkedWatched() {
        val session = session()
        session.save(PlaybackProgress(6_000_000, 0, 7_200_000, isLive = true))
        session.onEnded(PlaybackProgress(7_200_000, 7_200_000, 7_200_000, isLive = true))
        settle()
        runBlocking { assertThat(watch.progress(id)).isNull() }
    }

    @Test
    fun startGoesIntoHistoryAndTheEndMarksWatched() {
        val session = session()
        session.onStarted(video)
        session.onStarted(video)
        session.save(PlaybackProgress(590_000, 0, 600_000))
        session.onEnded(PlaybackProgress(600_000, 600_000, 600_000))
        settle()
        runBlocking {
            assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly(id)
            assertThat(watch.progress(id)?.isFinished).isTrue()
            assertThat(watch.resumePositionMs(id)).isEqualTo(0)
        }
    }

    @Test
    fun watchLaterToggles() {
        val session = session()
        session.setSavedForLater(video, true)
        settle()
        runBlocking { assertThat(watch.isSaved(id).first()).isTrue() }
        session.setSavedForLater(video, false)
        settle()
        runBlocking { assertThat(watch.isSaved(id).first()).isFalse() }
    }

    @Test
    fun aSingleVideoEndsOnTheEndCard() {
        val vm = PlayerViewModel(id, StreamRepository(FakeUiSource()), session())
        vm.onEnded(PlaybackProgress(600_000, 600_000, 600_000))
        assertThat(vm.end.value).isEqualTo(PlaybackEnd.Finished)
        vm.onReplay()
        assertThat(vm.end.value).isNull()
    }

    @Test
    fun insideAPlaylistTheNextVideoFollowsAndTheLastOneEndsIt() {
        val next = UiFixtures.videos[1]
        val middle = PlayerViewModel(id, StreamRepository(FakeUiSource()), session(), nextInPlaylist = { NextVideo(next, 3) })
        middle.onEnded(PlaybackProgress(600_000, 600_000, 600_000))
        runBlocking { withTimeout(5_000) { middle.end.first { it != null } } }
        assertThat(middle.end.value).isEqualTo(PlaybackEnd.UpNext(next, 3))

        val last = PlayerViewModel(id, StreamRepository(FakeUiSource()), session(), nextInPlaylist = { null })
        last.onEnded(PlaybackProgress(600_000, 600_000, 600_000))
        runBlocking { withTimeout(5_000) { last.end.first { it != null } } }
        assertThat(last.end.value).isEqualTo(PlaybackEnd.PlaylistFinished)
    }

    // ---- v1.4: the Shorts player plays each Short through this same class ---------------------------------------------

    private val playable = StreamRepository(
        object : VideoSource by FakeUiSource() {
            override suspend fun stream(videoId: String, allowLowQuality: Boolean) = TestStreams.details(video)
        },
    )

    @Test
    fun aVideoResumesFromWhereItWasLeft_aShortNeverDoes() {
        runBlocking { watch.saveProgress(id, 300_000, 600_000) }
        val resumed = PlayerViewModel(id, playable, session())
        runBlocking { withTimeout(5_000) { resumed.load.first { it is PlayerViewModel.Load.Ready } } }
        assertThat(resumed.resumedFromMs.value).isEqualTo(300_000)

        // A Short is watched from its start each time (flipping back to one is not «continue»): the saved position is not even read.
        val short = PlayerViewModel(id, playable, session(), resumesFromSaved = false)
        runBlocking { withTimeout(5_000) { short.load.first { it is PlayerViewModel.Load.Ready } } }
        assertThat(short.resumedFromMs.value).isNull()
        assertThat(short.resume.positionMs).isEqualTo(0)
    }
}

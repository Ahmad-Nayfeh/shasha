package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.player.PlaybackLocking
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.PlayCheck
import com.ahmadnayfeh.shasha.policy.PlayedPart
import com.ahmadnayfeh.shasha.ui.FakeUiSource
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
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
 * What the player tells the playback lock (SPEC §4): every start, the natural end (with the playlist video
 * that follows), a replay, and a video that can no longer play; never a start on the end card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerLockHooksTest {
    private val video = UiFixtures.videos[0]
    private val id = video.videoId
    private val reports = mutableListOf<String>()
    private val locking = object : PlaybackLocking {
        override fun onStarted(videoId: String, playlistId: String?) {
            reports += "started $videoId ${playlistId ?: "-"}"
        }

        override fun onEnded(videoId: String, nextVideoId: String?, played: PlayedPart?) {
            reports += "ended $videoId ${nextVideoId ?: "-"}"
            playedParts += played
        }

        override fun onUnavailable(videoId: String) {
            reports += "unavailable $videoId"
        }
    }
    private val playedParts = mutableListOf<PlayedPart?>()
    private val end = PlaybackProgress(600_000, 600_000, 600_000)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A player whose check never answers: it stays loading, as while a video plays (no stream in JVM tests). */
    private fun player(playlistId: String? = null, next: (suspend () -> NextVideo?)? = null) = PlayerViewModel(
        videoId = id,
        streams = StreamRepository(FakeUiSource()),
        nextInPlaylist = next,
        gate = PlayGate { awaitCancellation() },
        lock = locking,
        playlistId = playlistId,
    )

    @Test
    fun aSingleVideo_locksAtEachStart_releasesAtTheEnd_andAReplayLocksAgain() {
        val vm = player()
        vm.onPlaybackStarted(video)
        vm.onPlaybackStarted(video) // back from Home: the lock keeps it
        vm.onEnded(end)
        vm.onPlaybackStarted(video) // back from Home on the end card: the ended video is not locked again
        vm.onReplay()
        assertThat(reports).containsExactly("started $id -", "started $id -", "ended $id -", "started $id -").inOrder()
    }

    @Test
    fun insideAPlaylist_theEndNamesTheVideoThatFollows_orNoneAfterTheLast() {
        val next = UiFixtures.videos[1]
        val middle = player("PL1") { NextVideo(next, 4) }
        middle.onPlaybackStarted(video)
        middle.onEnded(end)
        runBlocking { withTimeout(5_000) { middle.end.first { it != null } } }
        val last = player("PL1") { null }
        last.onEnded(end)
        runBlocking { withTimeout(5_000) { last.end.first { it != null } } }
        assertThat(reports).containsExactly("started $id PL1", "ended $id ${next.videoId}", "ended $id -").inOrder()
    }

    // ---- Skipping to the end (SPEC §4: leaving before the end) -------------------------------------

    @Test
    fun aVideoSkippedToItsEnd_isReportedWithWhatWasPlayed_andTheEndCardKnows() {
        val vm = player()
        vm.onPlaybackStarted(video)
        // 40 s watched, then the seek bar held to the end (the OSD, a media key or the phone: the player reports it).
        vm.onJumped(40_000, 600_000)
        vm.onEnded(end)
        assertThat(reports).containsExactly("started $id -", "ended $id -").inOrder()
        assertThat(playedParts.single()).isEqualTo(PlayedPart(playedMs = 40_000, leftMs = 600_000))
        assertThat(playedParts.single()!!.endedByItself).isFalse()
        assertThat(vm.skippedToEnd.value).isTrue()

        // «إعادة التشغيل»: a new start from 0, watched through this time.
        vm.onReplay()
        assertThat(vm.skippedToEnd.value).isFalse()
        vm.onJumped(600_000, 0)
        vm.onEnded(end)
        assertThat(playedParts.last()).isEqualTo(PlayedPart(playedMs = 600_000, leftMs = 600_000))
        assertThat(vm.skippedToEnd.value).isFalse()
    }

    @Test
    fun whatIsPlayed_addsUpAcrossHome_andSkippedParts_areLeftOut() {
        val vm = player()
        // Resumed at 2:00 of a 10-minute video: 8 minutes are left.
        vm.resume = ResumeState(positionMs = 120_000)
        vm.onPlaybackStarted(video)
        // Home at 5:00; back later, a new player continues there.
        vm.onReleased(ResumeState(positionMs = 300_000))
        vm.onPlaybackStarted(video)
        // A sponsor segment skipped (6:00 → 6:40), then the rest played to the end.
        vm.onJumped(360_000, 400_000)
        vm.onEnded(end)
        assertThat(playedParts.single()).isEqualTo(PlayedPart(playedMs = 180_000 + 60_000 + 200_000, leftMs = 480_000))
        assertThat(playedParts.single()!!.endedByItself).isTrue()
        assertThat(vm.skippedToEnd.value).isFalse()
    }

    @Test
    fun aLostNetwork_keepsTheLock_andIsTriedAgainWhenTheNetworkIsBack() {
        var checks = 0
        val offline = PlayerViewModel(
            id,
            StreamRepository(FakeUiSource()),
            gate = {
                checks++
                if (checks == 1) throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", "offline", null)
                awaitCancellation()
            },
            lock = locking,
        )
        runBlocking { withTimeout(5_000) { offline.load.first { it is PlayerViewModel.Load.Failed } } }
        assertThat(reports).isEmpty()
        offline.retryAfterNetwork()
        runBlocking { withTimeout(5_000) { offline.load.first { it == PlayerViewModel.Load.Loading } } }
        assertThat(checks).isEqualTo(2)
        assertThat(reports).isEmpty()

        // Anything YouTube answers ends it, and the network coming back does not retry that.
        val gone = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", null, null) }, lock = locking)
        runBlocking { withTimeout(5_000) { gone.load.first { it is PlayerViewModel.Load.Failed } } }
        gone.retryAfterNetwork()
        assertThat(gone.load.value).isInstanceOf(PlayerViewModel.Load.Failed::class.java)
        assertThat(reports).containsExactly("unavailable $id")
    }

    @Test
    fun anotherVideosLock_neverReleasesThisOne() {
        val refused = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { PlayCheck.Denied(DenyReason.LOCKED) }, lock = locking)
        runBlocking { withTimeout(5_000) { refused.load.first { it is PlayerViewModel.Load.Denied } } }
        assertThat(reports).isEmpty()
    }

    @Test
    fun aVideoThatCannotPlay_releases_butTimeUpOnlyPausesIt() {
        val refused = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { PlayCheck.Denied(DenyReason.NOT_IN_ALLOWLIST) }, lock = locking)
        runBlocking { withTimeout(5_000) { refused.load.first { it is PlayerViewModel.Load.Denied } } }
        val failed = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { PlayCheck.Allowed(null) }, lock = locking)
        runBlocking { withTimeout(5_000) { failed.load.first { it is PlayerViewModel.Load.Failed } } }
        assertThat(reports).containsExactly("unavailable $id", "unavailable $id").inOrder()

        reports.clear()
        val timeUp = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { PlayCheck.Denied(DenyReason.TIME_UP) }, lock = locking)
        runBlocking { withTimeout(5_000) { timeUp.load.first { it is PlayerViewModel.Load.Denied } } }
        val night = PlayerViewModel(id, StreamRepository(FakeUiSource()), gate = { PlayCheck.Denied(DenyReason.OUTSIDE_SCHEDULE) }, lock = locking)
        runBlocking { withTimeout(5_000) { night.load.first { it is PlayerViewModel.Load.Denied } } }
        assertThat(reports).isEmpty()
    }
}

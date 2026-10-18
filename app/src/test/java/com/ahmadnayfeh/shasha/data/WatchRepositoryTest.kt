package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WatchRepositoryTest {
    private lateinit var db: ShashaDatabase
    private val profile = MutableProfile()
    private var now = NOW_MS
    private lateinit var watch: WatchRepository

    private val ch = channelId(1)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        watch = WatchRepository(db, profile, clock = { now })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun progress_resumesFromTheSavedPosition_unlessBarelyStartedOrFinished() = runBlocking<Unit> {
        watch.saveProgress("v1", positionMs = 125_000, durationMs = 600_000)
        assertThat(watch.resumePositionMs("v1")).isEqualTo(125_000)

        watch.saveProgress("v1", positionMs = 3_000, durationMs = 600_000)
        assertThat(watch.resumePositionMs("v1")).isEqualTo(0)

        watch.saveProgress("v1", positionMs = 595_000, durationMs = 600_000) // in the last 10 s
        assertThat(watch.progress("v1")!!.isFinished).isTrue()
        assertThat(watch.resumePositionMs("v1")).isEqualTo(0)

        watch.saveProgress("movie", positionMs = 6_900_000, durationMs = 7_200_000) // credits: past 95 %
        assertThat(watch.resumePositionMs("movie")).isEqualTo(0)

        assertThat(watch.resumePositionMs("never-seen")).isEqualTo(0)
    }

    @Test
    fun progress_ignoresLivesAndClampsThePosition() = runBlocking<Unit> {
        watch.saveProgress("live", positionMs = 50_000, durationMs = 0)
        assertThat(watch.progress("live")).isNull()

        watch.saveProgress("v1", positionMs = 900_000, durationMs = 600_000)
        assertThat(watch.progress("v1")!!.positionMs).isEqualTo(600_000)
    }

    @Test
    fun markWatched_andClear() = runBlocking<Unit> {
        watch.saveProgress("v1", positionMs = 60_000, durationMs = 600_000)
        watch.markWatched("v1", durationMs = 0) // unknown here: the saved duration is used
        assertThat(watch.progress("v1")!!.positionMs).isEqualTo(600_000)
        assertThat(watch.progress("v1")!!.isFinished).isTrue()

        watch.markWatched("v2", durationMs = 0) // nothing known: nothing to mark
        assertThat(watch.progress("v2")).isNull()

        watch.clearProgress("v1")
        assertThat(watch.progress("v1")).isNull()
    }

    @Test
    fun observeProgress_forTheCardsOnScreen_followsTheProfile() = runBlocking<Unit> {
        watch.saveProgress("v1", 60_000, 600_000)
        profile.id.value = "kid"
        watch.saveProgress("v2", 30_000, 300_000)

        assertThat(watch.observeProgress(listOf("v1", "v2", "v3")).first().keys).containsExactly("v2")
        profile.id.value = ADULT_PROFILE_ID
        assertThat(watch.observeProgress(listOf("v1", "v2")).first().keys).containsExactly("v1")
        assertThat(watch.observeProgress(emptyList()).first()).isEmpty()
    }

    @Test
    fun history_oneRowPerVideo_newestFirst_perProfile() = runBlocking<Unit> {
        watch.addToHistory(video("v1", ch, NOW_MS))
        now += 1_000
        watch.addToHistory(video("v2", ch, NOW_MS, durationSeconds = 212))
        now += 1_000
        watch.addToHistory(video("v1", ch, NOW_MS, title = "New title"))

        val history = watch.observeHistory().first()
        assertThat(history.map { it.videoId }).containsExactly("v1", "v2").inOrder()
        assertThat(history.first().title).isEqualTo("New title")
        assertThat(history.first().watchedAtMs).isEqualTo(NOW_MS + 2_000)
        assertThat(history.last().durationSeconds).isEqualTo(212)

        profile.id.value = "kid"
        assertThat(watch.observeHistory().first()).isEmpty()
        watch.addToHistory(video("k1", ch, NOW_MS))
        watch.clearHistory()
        profile.id.value = ADULT_PROFILE_ID
        watch.removeFromHistory("v2")
        assertThat(watch.observeHistory().first().map { it.videoId }).containsExactly("v1")
    }

    @Test
    fun watchLater_addRemoveAndIsSaved() = runBlocking<Unit> {
        watch.addToWatchLater(video("v1", ch, NOW_MS))
        now += 1_000
        watch.addToWatchLater(video("v2", ch, NOW_MS))
        now += 1_000
        watch.addToWatchLater(video("v1", ch, NOW_MS)) // already saved: keeps its place

        assertThat(watch.observeWatchLater().first().map { it.videoId }).containsExactly("v2", "v1").inOrder()
        assertThat(watch.isSaved("v1").first()).isTrue()

        watch.removeFromWatchLater("v1")
        assertThat(watch.isSaved("v1").first()).isFalse()
        profile.id.value = "kid"
        assertThat(watch.observeWatchLater().first()).isEmpty()
        assertThat(watch.isSaved("v2").first()).isFalse()
    }

    @Test
    fun savedPlaylists_newestFirst_perProfile_andToggled() = runBlocking<Unit> {
        val travel = PlaylistItem("PLtravel", "رحلات", "قناة", null, 12)
        val cooking = PlaylistItem("PLcooking", "طبخ", null, "https://i.ytimg.com/vi/x/hqdefault.jpg", null)
        watch.savePlaylist(travel)
        now += 1_000
        assertThat(watch.toggleSavedPlaylist(cooking)).isTrue()
        assertThat(watch.observeSavedPlaylists().first()).containsExactly(cooking, travel).inOrder()
        assertThat(watch.observeSavedPlaylistIds().first()).containsExactly("PLtravel", "PLcooking")

        // Saving again refreshes it and moves it to the top.
        now += 1_000
        watch.savePlaylist(travel.copy(videoCount = 13))
        assertThat(watch.observeSavedPlaylists().first().map { it.playlistId to it.videoCount })
            .containsExactly("PLtravel" to 13L, "PLcooking" to null).inOrder()

        // Another profile has its own library.
        profile.id.value = "kid"
        assertThat(watch.observeSavedPlaylists().first()).isEmpty()
        assertThat(watch.observeSavedPlaylistIds().first()).isEmpty()
        profile.id.value = ADULT_PROFILE_ID

        assertThat(watch.toggleSavedPlaylist(cooking)).isFalse()
        assertThat(watch.observeSavedPlaylists().first().map { it.playlistId }).containsExactly("PLtravel")
        watch.removeSavedPlaylist("PLtravel")
        assertThat(watch.observeSavedPlaylists().first()).isEmpty()
    }

    @Test
    fun savedPlaylists_goWithTheirProfile() = runBlocking<Unit> {
        val profiles = ProfileRepository(db)
        profiles.ensureOwner("الكبار")
        val kid = com.ahmadnayfeh.shasha.policy.Profile.newChild("سارة", id = "kid")
        profiles.upsert(kid)
        profile.id.value = "kid"
        watch.savePlaylist(PlaylistItem("PLkid", "أناشيد", null, null, 3))
        profile.id.value = ADULT_PROFILE_ID
        watch.savePlaylist(PlaylistItem("PLadult", "وثائقي", null, null, 3))

        assertThat(profiles.delete("kid")).isEqualTo(DeleteProfileResult.Deleted)
        profile.id.value = "kid"
        assertThat(watch.observeSavedPlaylists().first()).isEmpty()
        profile.id.value = ADULT_PROFILE_ID
        assertThat(watch.observeSavedPlaylists().first().map { it.playlistId }).containsExactly("PLadult")
    }
}

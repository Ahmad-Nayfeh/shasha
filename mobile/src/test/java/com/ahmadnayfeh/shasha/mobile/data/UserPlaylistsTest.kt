package com.ahmadnayfeh.shasha.mobile.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistItemEntity
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The viewer's own playlists on a real (in-memory) Room database: the repository and the DAO queries under it. A list plays in the
 * order its videos were added, a video is in a list once and can be in several lists, and deleting a list takes its videos with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UserPlaylistsTest {
    private val db = inMemoryDatabase()
    private var now = NOW_MS
    private val playlists = UserPlaylistRepository(db.userPlaylists()) { now++ }

    @After
    fun tearDown() = db.close()

    private suspend fun makeLists(vararg names: String): List<Long> = names.map { checkNotNull(playlists.create(it)) }

    // --- making, renaming, deleting -------------------------------------------------------------------------------

    @Test
    fun aListIsMadeWithATrimmedName_aBlankNameMakesNone() = runBlocking<Unit> {
        val id = playlists.create("  كرتون  ")

        assertThat(id).isNotNull()
        assertThat(playlists.playlist(id!!).first()?.name).isEqualTo("كرتون")
        assertThat(playlists.create("   ")).isNull()
        assertThat(playlists.create("")).isNull()
        assertThat(playlists.playlists.first()).hasSize(1)
    }

    @Test
    fun aLongNameIsCutAtTheLimit() = runBlocking<Unit> {
        val id = checkNotNull(playlists.create("ا".repeat(UserPlaylistRepository.MAX_NAME + 30)))

        assertThat(playlists.playlist(id).first()?.name).hasLength(UserPlaylistRepository.MAX_NAME)
        assertThat(UserPlaylistRepository.cleanName("  a  ")).isEqualTo("a")
        assertThat(UserPlaylistRepository.cleanName(" \n ")).isNull()
    }

    @Test
    fun theListsComeInTheOrderTheyWereMadeAndDoNotMoveWhenSomethingIsAddedToOne() = runBlocking<Unit> {
        val (first, second) = makeLists("first", "second")

        playlists.add(second, video("a")) // the later list is the one that is used

        assertThat(playlists.playlists.first().map { it.name }).containsExactly("first", "second").inOrder()
        assertThat(playlists.playlists.first().map { it.id }).containsExactly(first, second).inOrder()
    }

    @Test
    fun renamingChangesOnlyTheName_aBlankNameOrAMissingListChangesNothing() = runBlocking<Unit> {
        val (id) = makeLists("old")
        playlists.add(id, video("a"))

        assertThat(playlists.rename(id, "  new ")).isTrue()
        assertThat(playlists.playlist(id).first()?.name).isEqualTo("new")
        assertThat(playlists.videosNow(id).map { it.videoId }).containsExactly("a")

        assertThat(playlists.rename(id, "   ")).isFalse()
        assertThat(playlists.rename(id + 99, "x")).isFalse()
        assertThat(playlists.playlist(id).first()?.name).isEqualTo("new")
    }

    @Test
    fun deletingAListTakesItsVideosWithIt_andLeavesTheOthers() = runBlocking<Unit> {
        val (gone, kept) = makeLists("gone", "kept")
        playlists.add(gone, video("a"))
        playlists.add(kept, video("a"))
        playlists.add(kept, video("b"))

        playlists.delete(gone)

        assertThat(playlists.playlist(gone).first()).isNull()
        assertThat(db.userPlaylists().items(gone)).isEmpty()
        assertThat(playlists.videosNow(kept).map { it.videoId }).containsExactly("a", "b").inOrder()
        assertThat(playlists.playlists.first().map { it.id }).containsExactly(kept)
    }

    // --- the videos of a list -------------------------------------------------------------------------------------

    @Test
    fun aListPlaysInTheOrderItsVideosWereAdded_evenWithTheSameClockTick() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        val frozen = UserPlaylistRepository(db.userPlaylists()) { NOW_MS } // every video added at the same millisecond

        for (name in listOf("c", "a", "b")) frozen.add(id, video(name))

        assertThat(frozen.videosNow(id).map { it.videoId }).containsExactly("c", "a", "b").inOrder()
        assertThat(frozen.videos(id).first().map { it.videoId }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun aVideoIsInAListOnce_andCanBeInSeveral() = runBlocking<Unit> {
        val (one, two) = makeLists("one", "two")

        assertThat(playlists.add(one, video("a"))).isTrue()
        assertThat(playlists.add(one, video("a"))).isFalse()
        assertThat(playlists.add(two, video("a"))).isTrue()

        assertThat(playlists.videosNow(one)).hasSize(1)
        assertThat(playlists.containing("a").first()).containsExactly(one, two)
        assertThat(playlists.containing("other").first()).isEmpty()
    }

    @Test
    fun removingAVideoLeavesTheOrderOfTheRest_andANewOneGoesToTheEnd() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        for (name in listOf("a", "b", "c")) playlists.add(id, video(name))

        playlists.remove(id, "b")
        playlists.add(id, video("d"))

        assertThat(playlists.videosNow(id).map { it.videoId }).containsExactly("a", "c", "d").inOrder()
        // Taking a video out that is not there is harmless.
        playlists.remove(id, "zzz")
        assertThat(playlists.videosNow(id)).hasSize(3)
    }

    @Test
    fun toggleAddsWhenAbsentAndRemovesWhenPresent() = runBlocking<Unit> {
        val (id) = makeLists("mine")

        assertThat(playlists.toggle(id, video("a"))).isTrue()
        assertThat(playlists.containing("a").first()).containsExactly(id)
        assertThat(playlists.toggle(id, video("a"))).isFalse()
        assertThat(playlists.containing("a").first()).isEmpty()
    }

    @Test
    fun newPlaylistFromAVideoHoldsThatVideo_aBlankNameMakesNothing() = runBlocking<Unit> {
        val id = checkNotNull(playlists.createWith("طبخ", video("a")))

        assertThat(playlists.videosNow(id).map { it.videoId }).containsExactly("a")
        assertThat(playlists.createWith("  ", video("b"))).isNull()
        assertThat(playlists.playlists.first()).hasSize(1)
    }

    @Test
    fun addingToAListThatIsGoneIsRefusedNotACrash() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        playlists.delete(id)

        assertThat(playlists.add(id, video("a"))).isFalse()
        assertThat(playlists.toggle(id, video("a"))).isFalse()
        assertThat(db.userPlaylists().items(id)).isEmpty()
    }

    @Test
    fun theSummariesCountTheVideosAndShowTheFirstOnesPicture() = runBlocking<Unit> {
        val (full, empty) = makeLists("full", "empty")
        playlists.add(full, video("first"))
        playlists.add(full, video("second"))

        val summaries = playlists.playlists.first()

        assertThat(summaries.map { it.name to it.itemCount }).containsExactly("full" to 2, "empty" to 0).inOrder()
        assertThat(summaries.first { it.id == full }.coverUrl).isEqualTo(video("first").thumbnailUrl)
        assertThat(summaries.first { it.id == empty }.coverUrl).isNull()
    }

    @Test
    fun aVideoTheBuiltInFilterHoldsBackIsOutOfSightButNotDeleted() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        playlists.add(id, video("good", title = "Cooking pasta"))
        playlists.add(id, video("bad", title = "porn videos"))

        assertThat(playlists.videos(id).first().map { it.videoId }).containsExactly("good")
        assertThat(playlists.videosNow(id).map { it.videoId }).containsExactly("good")
        assertThat(db.userPlaylists().items(id)).hasSize(2)
    }

    @Test
    fun aStoredVideoComesBackAsAVideoOfItsOwn() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        val original = video("a", title = "عنوان", channelId = "UCabc", channelName = "قناة", seconds = 125)
        playlists.add(id, original)

        val back = playlists.videosNow(id).single()

        assertThat(back.videoId).isEqualTo("a")
        assertThat(back.title).isEqualTo("عنوان")
        assertThat(back.channelId).isEqualTo("UCabc")
        assertThat(back.channelName).isEqualTo("قناة")
        assertThat(back.durationSeconds).isEqualTo(125)
        assertThat(back.url).isEqualTo(watchUrl("a"))
    }

    @Test
    fun theDaoRefusesADuplicateRowAndAssignsPositionsInOrder() = runBlocking<Unit> {
        val (id) = makeLists("mine")
        val dao = db.userPlaylists()
        fun row(videoId: String) = UserPlaylistItemEntity(id, videoId, position = 0, title = "t", channelId = null, channelName = null, thumbnailUrl = null, durationSeconds = null, addedAt = 1)

        assertThat(dao.append(row("a"))).isTrue()
        assertThat(dao.append(row("b"))).isTrue()
        assertThat(dao.append(row("a"))).isFalse()

        assertThat(dao.items(id).map { it.videoId to it.position }).containsExactly("a" to 0, "b" to 1).inOrder()
        assertThat(dao.nextPosition(id)).isEqualTo(2)
    }

    // --- the player's name for a list -----------------------------------------------------------------------------

    @Test
    fun aListIsPlayedUnderAnIdNoYouTubePlaylistCanHave() {
        assertThat(LocalPlaylistIds.of(42)).isEqualTo("local:42")
        assertThat(LocalPlaylistIds.parse("local:42")).isEqualTo(42L)
        assertThat(LocalPlaylistIds.parse("PLabcdefghijklmnop")).isNull()
        assertThat(LocalPlaylistIds.parse("local:abc")).isNull()
        assertThat(LocalPlaylistIds.parse(null)).isNull()
    }
}

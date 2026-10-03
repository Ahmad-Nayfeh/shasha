package com.ahmadnayfeh.shasha.mobile.ui

import com.ahmadnayfeh.shasha.mobile.FakeHistoryDao
import com.ahmadnayfeh.shasha.mobile.FakeProgressDao
import com.ahmadnayfeh.shasha.mobile.FakeUserPlaylistDao
import com.ahmadnayfeh.shasha.mobile.FakeWatchLaterDao
import com.ahmadnayfeh.shasha.mobile.MainDispatcherRule
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.UserPlaylistRepository
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistPickerViewModel
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistsViewModel
import com.ahmadnayfeh.shasha.mobile.ui.playlist.UserPlaylistViewModel
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The screens of the viewer's own playlists over in-memory DAOs: the Library's lists, one list, and the «Save to» sheet of a video. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private var now = NOW_MS
    private val dao = FakeUserPlaylistDao()
    private val playlists = UserPlaylistRepository(dao) { now++ }
    private val watchLaterDao = FakeWatchLaterDao()
    private val library = LibraryRepository(FakeHistoryDao(), watchLaterDao, FakeProgressDao()) { now++ }

    private fun TestScope.collecting(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    // --- the Library's lists ---------------------------------------------------------------------------------------

    @Test
    fun theLibraryListsStartUnreadAndThenFollowTheStoredLists() = runTest(main.dispatcher) {
        val vm = PlaylistsViewModel(playlists)
        assertThat(vm.lists.value).isNull() // not read yet: no «empty» message
        collecting(vm.lists)

        assertThat(vm.lists.value).isEmpty()

        vm.create("كرتون")
        vm.create("طبخ")
        assertThat(vm.lists.value!!.map { it.name }).containsExactly("كرتون", "طبخ").inOrder()

        val id = vm.lists.value!!.first().id
        vm.rename(id, "أفلام")
        assertThat(vm.lists.value!!.map { it.name }).containsExactly("أفلام", "طبخ").inOrder()

        vm.delete(id)
        assertThat(vm.lists.value!!.map { it.name }).containsExactly("طبخ")
    }

    @Test
    fun aBlankNameMakesNothingAndRenamesNothing() = runTest(main.dispatcher) {
        val vm = PlaylistsViewModel(playlists)
        collecting(vm.lists)
        vm.create("keep")
        val id = vm.lists.value!!.single().id

        vm.create("   ")
        vm.rename(id, "  ")

        assertThat(vm.lists.value!!.map { it.name }).containsExactly("keep")
    }

    // --- one list --------------------------------------------------------------------------------------------------

    @Test
    fun aListShowsItsVideosInOrderAndAVideoCanBeTakenOut() = runTest(main.dispatcher) {
        val id = checkNotNull(playlists.create("mine"))
        for (name in listOf("a", "b", "c")) playlists.add(id, video(name))
        val vm = UserPlaylistViewModel(id, playlists)
        assertThat(vm.videos.value).isNull()
        assertThat(vm.playlist.value).isEqualTo(Loadable.Loading)
        collecting(vm.videos)
        collecting(vm.playlist)

        assertThat(vm.videos.value!!.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
        assertThat((vm.playlist.value as Loadable.Loaded).value?.name).isEqualTo("mine")

        vm.remove("b")
        assertThat(vm.videos.value!!.map { it.videoId }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun renamingFromTheListsPageChangesItsTitle() = runTest(main.dispatcher) {
        val id = checkNotNull(playlists.create("old"))
        val vm = UserPlaylistViewModel(id, playlists)
        collecting(vm.playlist)

        vm.rename("new")

        assertThat((vm.playlist.value as Loadable.Loaded).value?.name).isEqualTo("new")
    }

    @Test
    fun deletingFromTheListsPageSaysSoWhenItIsDone() = runTest(main.dispatcher) {
        val id = checkNotNull(playlists.create("mine"))
        playlists.add(id, video("a"))
        val vm = UserPlaylistViewModel(id, playlists)
        collecting(vm.playlist)
        assertThat(vm.deleted.value).isFalse()

        vm.delete()

        assertThat(vm.deleted.value).isTrue()
        assertThat((vm.playlist.value as Loadable.Loaded).value).isNull()
        assertThat(dao.rows.value).isEmpty()
    }

    @Test
    fun aListThatDoesNotExistIsLoadedAsNothing() = runTest(main.dispatcher) {
        val vm = UserPlaylistViewModel(99, playlists)
        collecting(vm.playlist)

        assertThat(vm.playlist.value).isEqualTo(Loadable.Loaded(null))
    }

    // --- «Save to…» --------------------------------------------------------------------------------------------------

    private val watched = video("watched", title = "A video")

    @Test
    fun theSheetListsWatchLaterFirstAndThenEveryListWithItsCheckmark() = runTest(main.dispatcher) {
        val one = checkNotNull(playlists.create("one"))
        val two = checkNotNull(playlists.create("two"))
        playlists.add(two, watched)
        val vm = PlaylistPickerViewModel(watched, playlists, library)
        assertThat(vm.entries.value).isNull()
        collecting(vm.entries)

        val entries = vm.entries.value!!
        assertThat(entries.map { it.playlistId }).containsExactly(null, one, two).inOrder()
        assertThat(entries.map { it.contains }).containsExactly(false, false, true).inOrder()
        assertThat(entries.drop(1).map { it.name to it.count }).containsExactly("one" to 0, "two" to 1).inOrder()
    }

    @Test
    fun aTapPutsTheVideoInAListOrTakesItOut_aVideoCanBeInSeveral() = runTest(main.dispatcher) {
        val one = checkNotNull(playlists.create("one"))
        val two = checkNotNull(playlists.create("two"))
        val vm = PlaylistPickerViewModel(watched, playlists, library)
        collecting(vm.entries)

        vm.toggle(vm.entries.value!!.first { it.playlistId == one })
        vm.toggle(vm.entries.value!!.first { it.playlistId == two })
        assertThat(vm.entries.value!!.filter { it.contains }.map { it.playlistId }).containsExactly(one, two)

        vm.toggle(vm.entries.value!!.first { it.playlistId == one })
        assertThat(vm.entries.value!!.filter { it.contains }.map { it.playlistId }).containsExactly(two)
        assertThat(playlists.videosNow(one)).isEmpty()
        assertThat(playlists.videosNow(two).map { it.videoId }).containsExactly("watched")
    }

    @Test
    fun watchLaterIsToggledInTheSheetLikeAnyOtherList() = runTest(main.dispatcher) {
        val vm = PlaylistPickerViewModel(watched, playlists, library)
        collecting(vm.entries)

        vm.toggle(vm.entries.value!!.first { it.playlistId == null })
        assertThat(watchLaterDao.rows.value.map { it.videoId }).containsExactly("watched")
        assertThat(vm.entries.value!!.first().contains).isTrue()

        vm.toggle(vm.entries.value!!.first())
        assertThat(watchLaterDao.rows.value).isEmpty()
        assertThat(vm.entries.value!!.first().contains).isFalse()
    }

    @Test
    fun aNewPlaylistFromTheSheetHoldsTheVideoAtOnce() = runTest(main.dispatcher) {
        val vm = PlaylistPickerViewModel(watched, playlists, library)
        collecting(vm.entries)

        vm.createAndAdd("طبخ")
        vm.createAndAdd("  ") // a blank name makes nothing

        val entries = vm.entries.value!!
        assertThat(entries.map { it.name }.drop(1)).containsExactly("طبخ")
        assertThat(entries.last().contains).isTrue()
        assertThat(entries.last().count).isEqualTo(1)
    }

    @Test
    fun theWatchLaterRowOfTheSheetIsTheBuiltInOneAndNoUserListIsCalledThat() = runTest(main.dispatcher) {
        val vm = PlaylistPickerViewModel(watched, playlists, library)
        collecting(vm.entries)

        assertThat(vm.entries.value!!.single().playlistId).isNull()
        assertThat(vm.entries.value!!.single().count).isNull() // its size is not told here
    }
}

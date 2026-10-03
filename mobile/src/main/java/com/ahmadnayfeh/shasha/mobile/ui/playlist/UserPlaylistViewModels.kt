package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.UserPlaylistRepository
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistEntity
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistSummary
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The viewer's own playlists in the Library: the lists, and making, renaming and deleting them. */
class PlaylistsViewModel(private val playlists: UserPlaylistRepository) : ViewModel() {

    /** Null until the stored lists have been read (before that, no "empty" message). */
    val lists: StateFlow<List<UserPlaylistSummary>?> = playlists.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String) {
        viewModelScope.launch { playlists.create(name) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { playlists.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { playlists.delete(id) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PlaylistsViewModel(container.userPlaylists) }
        }
    }
}

/** One of the viewer's lists: its videos in the order they were added, and renaming, deleting and taking a video out. */
class UserPlaylistViewModel(private val id: Long, private val playlists: UserPlaylistRepository) : ViewModel() {

    /** Loading until the list is read; loaded with null when there is no such list (it was deleted). */
    val playlist: StateFlow<Loadable<UserPlaylistEntity?>> = playlists.playlist(id)
        .map { Loadable.Loaded(it) as Loadable<UserPlaylistEntity?> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    /** Null until the stored videos have been read. */
    val videos: StateFlow<List<VideoItem>?> = playlists.videos(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleted = MutableStateFlow(false)

    /** The list was deleted from this screen: the screen goes back. */
    val deleted: StateFlow<Boolean> = _deleted

    fun rename(name: String) {
        viewModelScope.launch { playlists.rename(id, name) }
    }

    fun remove(videoId: String) {
        viewModelScope.launch { playlists.remove(id, videoId) }
    }

    fun delete() {
        viewModelScope.launch {
            playlists.delete(id)
            _deleted.value = true
        }
    }

    companion object {
        fun factory(container: AppContainer, id: Long) = viewModelFactory {
            initializer { UserPlaylistViewModel(id, container.userPlaylists) }
        }
    }
}

/**
 * The «Save to» sheet of one video: «Watch later» and every list of the viewer, each with a checkmark when the video is in it.
 * A tap on a row puts the video in the list or takes it out; «New playlist» makes a list that holds the video at once.
 */
class PlaylistPickerViewModel(
    private val video: VideoItem,
    private val playlists: UserPlaylistRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    /**
     * One row of the sheet. [playlistId] null is «Watch later», the built-in list; [count] is how many videos the list holds
     * (unknown for «Watch later»).
     */
    data class Entry(val playlistId: Long?, val name: String, val count: Int?, val contains: Boolean)

    /** Null until read; «Watch later» first, then the viewer's lists in the order they were made. */
    val entries: StateFlow<List<Entry>?> = combine(
        playlists.playlists,
        playlists.containing(video.videoId),
        library.isInWatchLater(video.videoId),
    ) { lists, containing, savedForLater ->
        listOf(Entry(playlistId = null, name = "", count = null, contains = savedForLater)) +
            lists.map { Entry(it.id, it.name, it.itemCount, contains = it.id in containing) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(entry: Entry) {
        viewModelScope.launch {
            val id = entry.playlistId
            if (id == null) library.toggleWatchLater(video) else playlists.toggle(id, video)
        }
    }

    fun createAndAdd(name: String) {
        viewModelScope.launch { playlists.createWith(name, video) }
    }

    companion object {
        fun factory(container: AppContainer, video: VideoItem) = viewModelFactory {
            initializer { PlaylistPickerViewModel(video, container.userPlaylists, container.library) }
        }
    }
}

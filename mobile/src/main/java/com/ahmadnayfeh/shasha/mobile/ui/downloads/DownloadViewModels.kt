package com.ahmadnayfeh.shasha.mobile.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.common.UiError
import com.ahmadnayfeh.shasha.mobile.data.SettingsRepository
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.download.ActiveDownload
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadManager
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.mobile.download.OptionsSource
import com.ahmadnayfeh.shasha.youtube.download.DownloadOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The download sheet of one video: what it can be downloaded as, and the start of the chosen download. */
class DownloadSheetViewModel(
    private val source: OptionsSource,
    private val downloads: DownloadManager,
    settings: SettingsRepository,
    private val video: VideoItem,
) : ViewModel() {

    private val _state = MutableStateFlow<Loadable<DownloadOptions>>(Loadable.Loading)
    val state: StateFlow<Loadable<DownloadOptions>> = _state

    /** The quality the viewer prefers for downloads (Settings): the sheet marks the option that comes closest. */
    val preferredHeight: StateFlow<Int> = settings.settings.map { it.downloadQuality }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.ahmadnayfeh.shasha.mobile.data.AppSettings.DEFAULT_DOWNLOAD_QUALITY)

    init {
        load()
    }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            _state.value = try {
                Loadable.Loaded(source.options(video.videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(UiError.of(e))
            }
        }
    }

    /** Queues the download (the manager looks the streams up again when its turn comes). */
    fun start(kind: DownloadKind, height: Int) {
        // The manager's own scope does the work: leaving the sheet must not cancel it.
        downloads.requestInBackground(video, kind, height)
    }

    companion object {
        fun factory(container: AppContainer, video: VideoItem) = viewModelFactory {
            initializer { DownloadSheetViewModel(container.optionsSource, container.downloads, container.settings, video) }
        }
    }
}

/** A download as the list shows it: the stored row, and the live progress when it is the one running. */
data class DownloadRow(val entity: DownloadEntity, val progress: ActiveDownload?)

/** The Downloads screen: the queue and the finished files, with cancel, retry, delete and play. */
class DownloadsViewModel(private val downloads: DownloadManager) : ViewModel() {

    val rows: StateFlow<List<DownloadRow>?> = combine(downloads.downloads, downloads.active) { list, active ->
        list.map { DownloadRow(it, active?.takeIf { a -> a.id == it.id }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // A file the viewer removed in the gallery is no longer a download.
        viewModelScope.launch { downloads.pruneMissing() }
    }

    fun cancel(id: Long) {
        viewModelScope.launch { downloads.cancel(id) }
    }

    fun retry(id: Long) {
        viewModelScope.launch { downloads.retry(id) }
    }

    /** Removes the download and its file; the system's own question (for a file of an earlier install) goes to [onOutcome]. */
    fun delete(id: Long, onOutcome: (DeleteOutcome) -> Unit) {
        viewModelScope.launch { onOutcome(downloads.delete(id)) }
    }

    /** After the viewer agreed in the system's dialog, the file is gone: its row goes too. */
    fun forget(id: Long) {
        viewModelScope.launch { downloads.forget(id) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DownloadsViewModel(container.downloads) }
        }
    }
}

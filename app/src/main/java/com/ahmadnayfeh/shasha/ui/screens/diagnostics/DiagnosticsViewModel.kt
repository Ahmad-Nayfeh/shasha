package com.ahmadnayfeh.shasha.ui.screens.diagnostics

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.youtube.SourceException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Extracts every test video, one after the other (a weak TV CPU should not parse five YouTube pages
 * at once). Successful results stay in [StreamRepository]'s cache, so opening one plays at once.
 */
class DiagnosticsViewModel(private val streams: StreamRepository) : ViewModel() {
    private val _items = MutableStateFlow(TestVideo.entries.map { DiagnosticItem(it, CheckStatus.Waiting) })
    val items: StateFlow<List<DiagnosticItem>> = _items.asStateFlow()

    /** Row to focus when the screen comes back (e.g. after the player). */
    var focusedIndex: Int = 0

    private var job: Job? = null

    init {
        runChecks(fresh = false)
    }

    fun recheck() = runChecks(fresh = true)

    private fun runChecks(fresh: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            _items.value = TestVideo.entries.map { DiagnosticItem(it, CheckStatus.Waiting) }
            for (video in TestVideo.entries) {
                if (fresh) streams.invalidate(video.videoId)
                set(video, CheckStatus.Checking)
                val started = SystemClock.elapsedRealtime()
                val status = try {
                    val details = streams.stream(video.videoId)
                    CheckStatus.Ready(StreamSummary.of(details), SystemClock.elapsedRealtime() - started)
                } catch (e: SourceException) {
                    CheckStatus.Failed(e, SystemClock.elapsedRealtime() - started)
                }
                set(video, status)
            }
        }
    }

    private fun set(video: TestVideo, status: CheckStatus) = _items.update { list ->
        list.map { if (it.video == video) it.copy(status = status) else it }
    }
}

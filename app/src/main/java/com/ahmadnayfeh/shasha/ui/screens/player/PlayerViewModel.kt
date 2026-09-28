package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.StreamRepository
import com.ahmadnayfeh.shasha.player.ResumeState
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads one video's stream and remembers where playback was when the player was released. */
class PlayerViewModel(private val videoId: String, private val streams: StreamRepository) : ViewModel() {

    sealed interface Load {
        data object Loading : Load
        data class Ready(val stream: StreamDetails) : Load
        data class Failed(val error: SourceException) : Load
    }

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()

    /** Survives the player being released while the app is in the background. */
    var resume: ResumeState = ResumeState()

    init {
        viewModelScope.launch {
            _load.value = try {
                Load.Ready(streams.stream(videoId))
            } catch (e: SourceException) {
                Load.Failed(e)
            }
        }
    }

    /** Playback failed: show the error screen, and do not reuse this stream's (probably refused) URLs. */
    fun fail(error: SourceException) {
        _load.value = Load.Failed(error)
        viewModelScope.launch { streams.invalidate(videoId) }
    }
}

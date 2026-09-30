package com.ahmadnayfeh.shasha.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The profiles the phone chose («اختيار الملف», SPEC §5), once PolicyGate allowed the switch (or the profile was
 * the current one already). The TV's root follows them ([com.ahmadnayfeh.shasha.ui.app.RootViewModel]): while it
 * asks «من يشاهد الآن؟», a child profile the phone chose is entered. Nothing is kept for later: a choice nobody
 * follows right now is dropped.
 */
class PhoneProfileChoices {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val chosen: SharedFlow<String> = flow.asSharedFlow()

    fun chose(profileId: String) {
        flow.tryEmit(profileId)
    }
}

package com.ahmadnayfeh.shasha.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whose progress, history and "watch later" list the app reads and writes right now. */
interface CurrentProfile {
    val id: StateFlow<String>
}

/** The owner's profile: created on the first start (ProfileRepository.ensureOwner) under the id phase 2 used. */
const val ADULT_PROFILE_ID = "adult"

/** A profile that never changes, for tests and previews. The app uses [ActiveProfile]. */
class FixedProfile(id: String = ADULT_PROFILE_ID) : CurrentProfile {
    override val id: StateFlow<String> = MutableStateFlow(id).asStateFlow()
}

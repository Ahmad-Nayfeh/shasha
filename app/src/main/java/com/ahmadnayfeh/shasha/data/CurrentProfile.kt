package com.ahmadnayfeh.shasha.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whose progress, history and "watch later" list the app reads and writes right now. */
interface CurrentProfile {
    val id: StateFlow<String>
}

/** The owner's profile id while there are no profiles yet. */
const val ADULT_PROFILE_ID = "adult"

/**
 * Stand-in until profiles arrive (SPEC phase 3): everyone is the adult owner. Phase 3 replaces it in
 * AppContainer with the persisted current profile.
 */
class FixedProfile(id: String = ADULT_PROFILE_ID) : CurrentProfile {
    override val id: StateFlow<String> = MutableStateFlow(id).asStateFlow()
}

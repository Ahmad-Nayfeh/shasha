package com.ahmadnayfeh.shasha.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes. */
sealed interface Screen {
    /** Fixed test videos ("فيديوهات تجريبية"). Phase 1 start screen; later it moves under Settings → تشخيص. */
    @Serializable
    data object Diagnostics : Screen

    @Serializable
    data class Player(val videoId: String) : Screen

    /** Placeholder until the GitHub Releases updater arrives (SPEC phase 7). */
    @Serializable
    data object Updates : Screen
}

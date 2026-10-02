package com.ahmadnayfeh.shasha.policy

/** Which parts of the UI exist for a profile. Hidden features must not be rendered at all. */
data class Features(
    val search: Boolean,
    val textInput: Boolean,
    val comments: Boolean,
    val undoSponsorSkip: Boolean,
    val enterNeedsPin: Boolean,
    val exitNeedsPin: Boolean,
    val adultExitChallenge: Boolean,
    /** Channel pages exist for both kinds; their content still goes through the engine. */
    val channelBrowsing: Boolean,
    /**
     * «الفيديوهات المقترحة» (the player's «مقترحة» row, the end card's, the home's «مقترحة لك»): [Profile.showSuggestions].
     * Each suggestion still goes through [PolicyEngine.canSuggest].
     */
    val suggestions: Boolean = false,
)

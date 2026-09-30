package com.ahmadnayfeh.shasha.policy

sealed interface Decision {
    data object Allow : Decision
    data class Deny(val reason: DenyReason) : Decision

    val isAllowed: Boolean get() = this is Allow
}

enum class DenyReason {
    NOT_IN_ALLOWLIST,
    BLOCKED_CHANNEL,
    BLOCKED_KEYWORD,
    SHORTS_HIDDEN,
    TIME_UP,
    OUTSIDE_SCHEDULE,

    /**
     * A [PlaybackLock] is active; nothing else may start until it ends: the parent PIN for a child
     * ([UnlockMethod.PARENT_PIN]), the exit challenge for the adult ([UnlockMethod.EXIT_CHALLENGE]).
     */
    LOCKED,
}

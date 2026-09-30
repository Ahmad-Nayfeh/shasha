package com.ahmadnayfeh.shasha.policy

import androidx.annotation.StringRes
import com.ahmadnayfeh.shasha.R

/** The Arabic sentence that tells the viewer (or the phone) why the engine said no. */
@StringRes
fun DenyReason.messageRes(): Int = when (this) {
    DenyReason.NOT_IN_ALLOWLIST -> R.string.policy_deny_not_in_allowlist
    DenyReason.BLOCKED_CHANNEL -> R.string.policy_deny_blocked_channel
    DenyReason.BLOCKED_KEYWORD -> R.string.policy_deny_blocked_keyword
    DenyReason.SHORTS_HIDDEN -> R.string.policy_deny_shorts_hidden
    DenyReason.TIME_UP -> R.string.policy_deny_time_up
    DenyReason.OUTSIDE_SCHEDULE -> R.string.policy_deny_outside_schedule
    DenyReason.LOCKED -> R.string.policy_deny_locked
}

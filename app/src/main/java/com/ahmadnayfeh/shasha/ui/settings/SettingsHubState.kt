package com.ahmadnayfeh.shasha.ui.settings

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.datastore.preferences.core.Preferences
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.ReturnTime
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.remote.RemoteState
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus

/** The app's version (BuildConfig: name, and the versionCode CI sets) and the extractor's (SPEC §1's error screen shows it too). */
@Immutable
data class AppVersions(val name: String, val code: Int, val extractor: String)

/** The TV itself, for «تشخيص». */
@Immutable
data class DeviceFacts(val maker: String, val model: String, val android: String, val sdk: Int) {
    companion object {
        fun current() = DeviceFacts(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.VERSION.RELEASE.orEmpty(), Build.VERSION.SDK_INT)
    }
}

/**
 * «الخادم المحلي» at a glance: whether it is on and where it listens. Never the pairing token: that stays on the
 * pairing screen, behind the parent PIN.
 */
@Immutable
data class RemoteSummary(val enabled: Boolean, val status: PairingStatus, val address: String? = null) {
    companion object {
        fun of(enabled: Boolean, state: RemoteState): RemoteSummary = when {
            !enabled -> RemoteSummary(false, PairingStatus.OFF)
            state is RemoteState.Running -> RemoteSummary(true, PairingStatus.RUNNING, "${state.address.hostAddress}:${state.port}")
            state == RemoteState.NoNetwork -> RemoteSummary(true, PairingStatus.NO_NETWORK)
            state == RemoteState.Failed -> RemoteSummary(true, PairingStatus.FAILED)
            else -> RemoteSummary(true, PairingStatus.STARTING)
        }
    }
}

/**
 * Everything the settings' panes show. Null = not read yet. [unlocked]: the parent PIN was given (for a few
 * minutes, [com.ahmadnayfeh.shasha.ui.parental.ParentUnlock]); the parental panes show their content only then.
 */
@Immutable
data class SettingsHubState(
    val versions: AppVersions,
    val unlocked: Boolean = false,
    val profiles: List<Profile>? = null,
    /** The last two weeks per profile id, for «الإحصائيات» (read once that pane is open). */
    val usage: Map<String, UsageSummary>? = null,
    val subscriptionCount: Int? = null,
    val sponsorCategories: Set<SponsorCategory>? = null,
    val maxQuality: Int = PlaybackSettings.DEFAULT_MAX_QUALITY,
    val protection: ProtectionStatus? = null,
    /** The guard's newest measured return (SPEC acceptance: Home → back within 2 s). */
    val lastReturn: ReturnTime? = null,
    val remote: RemoteSummary? = null,
    val syncHealth: SyncHealth? = null,
    val device: DeviceFacts? = null,
    /** "Now" for texts like «منذ 5 دقائق». */
    val nowMs: Long = 0,
    /** The parent's adjustable behaviour (Serious Mode, timings, the PIN's brake). */
    val behavior: Behavior? = null,
    /** The phone remote's chosen port. */
    val remotePort: Int? = null,
)

/**
 * What the settings can do. The route asks for the parent PIN before the parental ones ([unlock] asks for it
 * alone); the screen only calls them.
 */
@Stable
class SettingsHubActions(
    val unlock: () -> Unit = {},
    val openProfiles: () -> Unit = {},
    val openProfile: (profileId: String) -> Unit = {},
    val openChangePin: () -> Unit = {},
    val openBlocklist: () -> Unit = {},
    val openAllowlist: (profileId: String) -> Unit = {},
    val openStats: () -> Unit = {},
    val openProtection: () -> Unit = {},
    val openRemote: () -> Unit = {},
    val openBackup: () -> Unit = {},
    val openImport: () -> Unit = {},
    val openUpdates: () -> Unit = {},
    val openDiagnostics: () -> Unit = {},
    val setQuota: (profileId: String, minutes: Int?) -> Unit = { _, _ -> },
    val setSchedule: (profileId: String, schedule: Schedule?) -> Unit = { _, _ -> },
    val setSponsorCategory: (category: SponsorCategory, enabled: Boolean) -> Unit = { _, _ -> },
    val resetSponsorCategories: () -> Unit = {},
    val setMaxQuality: (height: Int) -> Unit = {},
    val setRemoteEnabled: (enabled: Boolean) -> Unit = {},
    val behavior: BehaviorActions = BehaviorActions(),
)

/** Changes to [Behavior] (every one behind the parent PIN: the route guards them). */
@Stable
class BehaviorActions(
    val setSeriousMode: (Boolean) -> Unit = {},
    val setAdultEscape: (Boolean) -> Unit = {},
    val setAdultEntryPin: (Boolean) -> Unit = {},
    val setVoiceLanguage: (String) -> Unit = {},
    val setNumber: (key: Preferences.Key<Int>, value: Int) -> Unit = { _, _ -> },
    val setPhraseMode: (PhraseMode) -> Unit = {},
    /** Already checked with AdultExitChallenge.cleanPhrase. */
    val addPhrase: (String) -> Unit = {},
    val removePhrase: (String) -> Unit = {},
    val setCustomPhrase: (String) -> Unit = {},
    val setExtraMinutes: (List<Int>) -> Unit = {},
    val setDefaultSpeed: (Float) -> Unit = {},
    /** These settings back to their defaults. */
    val reset: (keys: List<Preferences.Key<*>>) -> Unit = {},
    val setPort: (Int) -> Unit = {},
    val resetPort: () -> Unit = {},
    /** «إرجاع كل الإعدادات إلى وضعها الأصلي» (already confirmed). */
    val resetEverything: () -> Unit = {},
)

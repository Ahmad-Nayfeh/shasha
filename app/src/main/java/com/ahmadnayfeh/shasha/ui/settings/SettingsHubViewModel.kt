package com.ahmadnayfeh.shasha.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.Preferences
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.AppTheme
import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.BehaviorSettings
import com.ahmadnayfeh.shasha.data.PhraseMode
import com.ahmadnayfeh.shasha.data.PlaybackSettings
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SettingsReset
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.remote.RemoteSettings
import com.ahmadnayfeh.shasha.remote.RemoteState
import com.ahmadnayfeh.shasha.sponsorblock.SponsorBlockSettings
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The data behind the settings' panes, each read only while the settings are shown. Changes are written on
 * [appScope]: a choice made just before leaving must still be stored.
 */
class SettingsHubViewModel(
    private val profileRepository: ProfileRepository,
    subscriptions: SubscriptionRepository,
    private val sponsorBlock: SponsorBlockSettings,
    private val playback: PlaybackSettings,
    private val remoteSettings: RemoteSettings,
    remoteState: StateFlow<RemoteState>,
    private val usageRepository: UsageRepository,
    private val engine: PolicyEngine,
    private val zone: () -> ZoneId,
    private val appScope: CoroutineScope,
    private val behaviorSettings: BehaviorSettings? = null,
    /** The phone remote listens again (a new port). */
    private val restartRemote: suspend () -> Unit = {},
    /** v1.1.1: a phone is paired with the TV (the adult's escape may be turned off only then). */
    private val phonePaired: suspend () -> Boolean = { true },
) : ViewModel() {

    val profiles: StateFlow<List<Profile>?> = profileRepository.observeAll().stateIn(viewModelScope, WHILE_SHOWN, null)

    val subscriptionCount: StateFlow<Int?> = subscriptions.observe().map { it.size }.stateIn(viewModelScope, WHILE_SHOWN, null)

    val sponsorCategories: StateFlow<Set<SponsorCategory>?> = sponsorBlock.categories.stateIn(viewModelScope, WHILE_SHOWN, null)

    val maxQuality: StateFlow<Int> = playback.maxQuality

    val behavior: StateFlow<Behavior?> = behaviorSettings?.values ?: MutableStateFlow(null)

    val remotePort: StateFlow<Int?> = remoteSettings.port.stateIn(viewModelScope, WHILE_SHOWN, null)

    val remote: StateFlow<RemoteSummary?> = combine(remoteSettings.enabled, remoteState) { enabled, state -> RemoteSummary.of(enabled, state) }
        .stateIn(viewModelScope, WHILE_SHOWN, null)

    private val _usage = MutableStateFlow<Map<String, UsageSummary>?>(null)

    /** «الإحصائيات»: each profile's last two weeks, read by [loadUsage]. */
    val usage: StateFlow<Map<String, UsageSummary>?> = _usage.asStateFlow()

    /** Reads every profile's summary again (the pane opened: the numbers may have moved since). */
    fun loadUsage() {
        viewModelScope.launch {
            try {
                val today = engine.today()
                _usage.value = profileRepository.all().associate { it.id to usageRepository.summary(it.id, today, zone()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the usage summaries", e)
                _usage.value = emptyMap()
            }
        }
    }

    fun setQuota(profileId: String, minutes: Int?) = write { profileRepository.update(profileId) { it.copy(dailyQuotaMinutes = minutes) } }

    fun setSchedule(profileId: String, schedule: Schedule?) = write { profileRepository.update(profileId) { it.copy(schedule = schedule) } }

    fun setSponsorCategory(category: SponsorCategory, enabled: Boolean) = write { sponsorBlock.setEnabled(category, enabled) }

    fun resetSponsorCategories() = write { sponsorBlock.reset() }

    fun setMaxQuality(height: Int) = write { playback.setMaxQuality(height) }

    /** [afterEnabling] starts the server's service (the service stops itself when switched off). */
    fun setRemoteEnabled(enabled: Boolean, afterEnabling: () -> Unit) = write {
        remoteSettings.setEnabled(enabled)
        if (enabled) afterEnabling()
    }

    fun setSeriousModeChildren(on: Boolean) = behave { it.setSeriousModeChildren(on) }

    fun setSeriousModeAdult(on: Boolean) = behave { it.setSeriousModeAdult(on) }

    /**
     * The adult's way out before the end. Turning it OFF needs a paired phone (v1.1.1): the phone's parent section is then
     * the only way to end an adult's video early, and every adult video waits for its «تأكيد». Without one nothing is
     * stored and [refused] says why.
     */
    fun setAdultEscape(on: Boolean, refused: () -> Unit = {}) = behave { settings ->
        if (!settings.setAdultEscape(on, phonePaired)) refused()
    }

    fun setAdultEntryPin(on: Boolean) = behave { it.setAdultEntryPin(on) }

    fun setVoiceLanguage(tag: String) = behave { it.setVoiceLanguage(tag) }

    fun setAppLanguage(language: AppLanguage) = behave { it.setAppLanguage(language) }

    fun setAppTheme(theme: AppTheme) = behave { it.setAppTheme(theme) }

    fun setNumber(key: Preferences.Key<Int>, value: Int) = behave { it.setNumber(key, value) }

    fun setPhraseMode(mode: PhraseMode) = behave { it.setPhraseMode(mode) }

    fun addPhrase(phrase: String) = behave { it.addPhrase(phrase) }

    fun removePhrase(phrase: String) = behave { it.removePhrase(phrase) }

    fun setCustomPhrase(phrase: String) = behave { it.setCustomPhrase(phrase) }

    fun setExtraMinutes(minutes: List<Int>) = behave { it.setExtraMinutes(minutes) }

    fun setDefaultSpeed(speed: Float) = behave { it.setDefaultSpeed(speed) }

    fun resetBehavior(keys: List<Preferences.Key<*>>) = behave { it.reset(*keys.toTypedArray()) }

    /** A new port: stored, then the server moves to it (when it runs). */
    fun setPort(port: Int) = write {
        remoteSettings.setPort(port)
        restartRemote()
    }

    fun resetPort() = write {
        remoteSettings.resetPort()
        restartRemote()
    }

    /**
     * «إرجاع كل الإعدادات إلى وضعها الأصلي»: every adjustable setting, the SponsorBlock categories, the quality cap
     * and the server's port. Never the profiles, the PIN itself, the subscriptions, the history or the lists.
     */
    fun resetEverything(afterDone: () -> Unit = {}) = write {
        SettingsReset(behaviorSettings, sponsorBlock, playback, remoteSettings).resetAll()
        restartRemote()
        afterDone()
    }

    private fun behave(block: suspend (BehaviorSettings) -> Unit) {
        val settings = behaviorSettings ?: return
        write { block(settings) }
    }

    private fun write(block: suspend () -> Unit) {
        appScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refused change (the rules' checks) or a failing disk: nothing is stored, the pane shows what is.
                Log.w(TAG, "A settings change was not stored", e)
            }
        }
    }

    private companion object {
        const val TAG = "SettingsHub"
        val WHILE_SHOWN = SharingStarted.WhileSubscribed(5_000)
    }
}

package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.DeleteProfileResult
import com.ahmadnayfeh.shasha.data.ParentPinRepository
import com.ahmadnayfeh.shasha.data.PinResult
import com.ahmadnayfeh.shasha.data.ProfileRepository
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.UsageRepository
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.toCheck
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** الملفات: every profile, and a new child profile. */
class ProfilesViewModel(private val profiles: ProfileRepository) : ViewModel() {
    val all: StateFlow<List<Profile>?> = profiles.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A child profile with the default rules (1 hour a day, 07:00–22:00, shorts hidden) and the household's blocks. */
    suspend fun createChild(name: String, avatar: String): Profile {
        val child = Profile.newChild(name.trim(), avatar = avatar).copy(blocklist = ParentalLists.householdBlocklist(profiles.all()))
        profiles.upsert(child)
        return child
    }
}

/** One profile's page: every change is one transaction ([ProfileRepository.update]). */
class ProfileEditViewModel(private val profileId: String, private val profiles: ProfileRepository) : ViewModel() {
    /** Null while loading, and once the profile is gone (deleted from here or from the phone). */
    val profile: StateFlow<Profile?> = profiles.observe(profileId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether this is the last adult (never deleted, never turned into a child). */
    val isLastAdult: StateFlow<Boolean> = profiles.observeAll()
        .map { all -> all.count { it.isAdult } <= 1 && all.any { it.id == profileId && it.isAdult } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun update(change: (Profile) -> Profile) {
        viewModelScope.launch {
            try {
                profiles.update(profileId, change)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalArgumentException) {
                // A change the rules refuse (e.g. the last adult becoming a child): nothing is stored.
            }
        }
    }

    suspend fun delete(): DeleteProfileResult = profiles.delete(profileId)
}

@Immutable
data class AllowlistState(val profile: Profile? = null, val subscriptions: List<ListedChannel> = emptyList())

/** A child's allowlist: what is on it, removing, and adding from the adult's subscriptions. */
class AllowlistViewModel(private val profileId: String, profiles: ProfileRepository, subscriptions: SubscriptionRepository) : ViewModel() {
    private val lists = ParentalLists(profiles)

    val state: StateFlow<AllowlistState> = combine(profiles.observe(profileId), subscriptions.observe()) { profile, subs ->
        AllowlistState(profile, subs.map { ListedChannel(it.channelId, it.name) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllowlistState())

    fun remove(id: String) {
        viewModelScope.launch { lists.disallow(profileId, id) }
    }

    fun toggle(channel: ListedChannel) {
        val allowed = state.value.profile?.allowlist?.hasChannel(channel.channelId) == true
        viewModelScope.launch { if (allowed) lists.disallow(profileId, channel.channelId) else lists.allowChannel(profileId, channel) }
    }
}

/**
 * قائمة الحظر: the household's blocked channels and title keywords (every profile). A blocked channel the
 * adult still subscribes to can be unsubscribed here: الاشتراكات no longer lists it (the rules hide it).
 * @param appScope for an unsubscription that must finish even if the screen closes.
 */
class BlocklistViewModel(
    profiles: ProfileRepository,
    private val subscriptions: SubscriptionRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val lists = ParentalLists(profiles)

    val blocklist: StateFlow<Blocklist?> = profiles.observeAll().map { ParentalLists.householdBlocklist(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The ids of the subscribed channels. */
    val subscribed: StateFlow<Set<String>> = subscriptions.observe().map { rows -> rows.mapTo(HashSet()) { it.channelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun unblock(channelId: String) {
        viewModelScope.launch { lists.unblockChannel(channelId) }
    }

    fun unsubscribe(channelId: String) {
        appScope.launch { subscriptions.remove(channelId) }
    }

    /** False when the word is blank or already blocked. */
    suspend fun addKeyword(keyword: String): Boolean = lists.addKeyword(keyword)

    fun removeKeyword(keyword: String) {
        viewModelScope.launch { lists.removeKeyword(keyword) }
    }
}

/** تغيير رمز PIN: the current PIN (checked like any other attempt), then the new one twice. */
class ChangePinViewModel(private val pin: ParentPinRepository) : ViewModel() {
    suspend fun checkCurrent(value: String): PinCheck = pin.verify(value).toCheck()

    suspend fun lockMillis(): Long = pin.remainingLockMillis()

    /** Stores [new] after checking [current] again (the one check that counts is the repository's). */
    suspend fun change(current: String, new: String): PinCheck = when (val result = pin.change(current, new)) {
        PinResult.Success -> PinCheck.Accepted
        else -> result.toCheck()
    }
}

/** الإحصائيات: one profile's last two weeks at a time (SPEC §3: no badges, no streaks). */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    profiles: ProfileRepository,
    private val usage: UsageRepository,
    private val engine: PolicyEngine,
    private val zone: () -> ZoneId,
) : ViewModel() {
    val all: StateFlow<List<Profile>> = profiles.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<String?>(null)

    /** The profile shown (the first until another is chosen). */
    val selected: StateFlow<Profile?> = combine(all, _selected) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Recomputed when the profile changes and whenever usage is recorded. */
    val summary: StateFlow<UsageSummary?> = combine(selected, usage.changes) { profile, _ -> profile }
        .mapLatest { profile -> profile?.let { usage.summary(it.id, engine.today(), zone()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(profileId: String) {
        _selected.value = profileId
    }

    /** The day [UsageSummary.days] ends with, for the chart's labels. */
    fun today(): Long = engine.today()
}

/** Not a ViewModel: the list of options the quota picker offers, in minutes (null = no limit). */
val QuotaOptions: List<Int?> = listOf(null, 15, 30, 45, 60, 90, 120, 180, 240)

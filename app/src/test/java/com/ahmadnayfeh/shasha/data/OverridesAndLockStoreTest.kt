package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.policy.Overrides
import com.ahmadnayfeh.shasha.policy.PlaybackLock
import com.ahmadnayfeh.shasha.policy.QuotaOverride
import com.ahmadnayfeh.shasha.policy.ScheduleOverride
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OverridesAndLockStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var stores: TestDataStores
    private val scope = testScope()

    private val today = 20_000L
    private val now = NOW_MS

    @Before
    fun setUp() {
        stores = TestDataStores(tmp.root)
    }

    @After
    fun tearDown() {
        scope.cancelAndWait()
        stores.closeAll()
    }

    @Test
    fun grants_addUp_andSurviveARestart() = runBlocking<Unit> {
        val repo = OverridesRepository(stores.open())
        assertThat(repo.current()).isEqualTo(Overrides.NONE)

        val first = Overrides(quota = listOf(QuotaOverride("kid", today, extraSeconds = 600)))
        val second = Overrides(schedule = listOf(ScheduleOverride("kid", now + HOUR_MS)))
        repo.add(first, today, now)
        repo.add(second, today, now)
        repo.add(Overrides.NONE, today, now)

        assertThat(repo.current()).isEqualTo(first + second)
        assertThat(repo.overrides.first()).isEqualTo(first + second)
        assertThat(OverridesRepository(stores.restart()).current()).isEqualTo(first + second)
    }

    @Test
    fun everyWriteAndTheDailyCleanup_dropWhatCanNoLongerApply() = runBlocking<Unit> {
        val repo = OverridesRepository(stores.open())
        val yesterday = QuotaOverride("kid", today - 1, extraSeconds = 600)
        val ended = ScheduleOverride("kid", now - 1)
        val running = ScheduleOverride("kid", now + HOUR_MS)
        repo.add(Overrides(listOf(yesterday), listOf(ended, running)), today - 1, now - 2)
        assertThat(repo.current().quota).containsExactly(yesterday)

        repo.prune(today, now)
        assertThat(repo.current()).isEqualTo(Overrides(schedule = listOf(running)))

        val otherKid = QuotaOverride("gone", today, unlimited = true)
        repo.add(Overrides(quota = listOf(otherKid)), today, now)
        repo.prune(today, now, profileIds = setOf("kid"))
        assertThat(repo.current()).isEqualTo(Overrides(schedule = listOf(running)))

        repo.prune(today, now + 2 * HOUR_MS)
        assertThat(repo.current()).isEqualTo(Overrides.NONE)
    }

    @Test
    fun anUnreadableValue_meansNoGrants() = runBlocking<Unit> {
        val store = stores.open()
        store.edit { it[PolicyStore.OVERRIDES] = "{\"quota\":[{\"profileId\":\"kid\",\"epochDay\":1,\"extraSeconds\":-5}]}" }
        assertThat(OverridesRepository(store).current()).isEqualTo(Overrides.NONE)
    }

    @Test
    fun playbackLock_isKept_untilTheEngineEndsIt_evenAcrossARestart() = runBlocking<Unit> {
        val locks = PlaybackLockStore(stores.open(), scope)
        assertThat(locks.current()).isNull()
        val lock = PlaybackLock("kid", UnlockMethod.PARENT_PIN, videoId = "v1", playlistId = "PL1", sinceEpochMs = now)
        locks.set(lock)
        assertThat(locks.current()).isEqualTo(lock)
        assertThat(withTimeout(5_000) { locks.lock.first { it != null } }).isEqualTo(lock)

        val afterRestart = PlaybackLockStore(stores.restart(), scope)
        assertThat(afterRestart.current()).isEqualTo(lock)
        afterRestart.set(null)
        assertThat(afterRestart.current()).isNull()
        assertThat(withTimeout(5_000) { afterRestart.lock.first { it == null } }).isNull()
    }
}
